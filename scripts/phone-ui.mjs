import {execFile} from 'node:child_process';
import {promisify} from 'node:util';
import fs from 'node:fs/promises';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import net from 'node:net';
const run = promisify(execFile);
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const serial = process.env.PHONEMIC_DEVICE || 'R5CY21RK3MJ';
const pause = ms => new Promise(resolve => setTimeout(resolve, ms));
const adb = async (...args) => (await run('adb', ['-s', serial, ...args], {windowsHide: true, maxBuffer: 8 * 1024 * 1024})).stdout;
const decode = value => value.replaceAll('&quot;', '"').replaceAll('&apos;', "'").replaceAll('&lt;', '<').replaceAll('&gt;', '>').replaceAll('&amp;', '&');
async function tree() {
  const report = await adb('shell', 'uiautomator', 'dump', '/sdcard/phonemic-window.xml');
  if (!report.includes('dumped to:')) throw Error('UI hierarchy was not refreshed; active waveform animations prevent an idle snapshot');
  const xml = await adb('shell', 'cat', '/sdcard/phonemic-window.xml');
  if (!xml.includes('package="com.phonemic"')) throw Error('PhoneMIC is not the active screen');
  return [...xml.matchAll(/<node\s[^>]+>/g)].map(match => Object.fromEntries([...match[0].matchAll(/([\w-]+)="([^"]*)"/g)].map(([, key, value]) => [key, decode(value)])));
}
async function tapText(value, contains = false) {
  for (let attempt = 0; attempt < 3; attempt++) {
    const nodes = await tree();
    const values = Array.isArray(value) ? value : [value];
    const matches = nodes.filter(n => values.some(label => ['text', 'content-desc'].some(key => contains ? n[key]?.includes(label) : n[key] === label)));
    const node = matches.find(n => n.clickable === 'true') || matches[0];
    if (node) {
      const b = node.bounds?.match(/\d+/g)?.map(Number);
      if (b && b[2] > b[0] && b[3] > b[1]) {
        await adb('shell', 'input', 'tap', String(Math.round((b[0] + b[2]) / 2)), String(Math.round((b[1] + b[3]) / 2)));
        await pause(350); return;
      }
    }
    await pause(400);
  }
  throw Error(`UI control not found: ${value}`);
}
async function capture(name) {
  const {stdout} = await run('adb', ['-s', serial, 'exec-out', 'screencap', '-p'], {windowsHide: true, encoding: 'buffer', maxBuffer: 12 * 1024 * 1024});
  await fs.writeFile(path.join(root, 'artifacts', `${name}.png`), stdout);
  console.log(`Captured ${name}`);
}
const command = process.argv[2] || 'capture';
if (['recording', 'shortcuts'].includes(command)) await adb('shell', 'input', 'keyevent', 'KEYCODE_HOME');
await adb('shell', 'am', 'start', '-n', 'com.phonemic/.MainActivity');
await pause(2200);
for (let attempt = 0; attempt < 3; attempt++) {
  const nodes = await tree();
  if (nodes.some(node => node['resource-id'] === 'voice-button')) break;
  await tapText(['返回', 'Back']);
}
for (let attempt = 0; attempt < 30; attempt++) {
  const nodes = await tree();
  if (nodes.some(node => node['resource-id'] === 'voice-button' && node.enabled === 'true')) break;
  if (attempt === 29) throw Error('Local model did not become ready');
  await pause(350);
}
if (command === 'shortcuts') {
  await tapText(['设置', 'Settings']);
  await capture('settings-current');
  await tapText(['发送快捷键', 'Send shortcut']);
  await tapText('Ctrl');
  await tapText('PageDown');
  await capture('shortcut-picker');
  await tapText(['完成', 'Done']);
  await tapText(['发送快捷键', 'Send shortcut']);
  const keys = await tree();
  if (!['Ctrl', 'PageDown'].every(label => keys.some(node => node['content-desc'] === label && node.checked === 'true'))) throw Error('Shortcut selection was not preserved');
  await tapText('Ctrl');
  await tapText('Enter');
  await tapText(['完成', 'Done']);
  await tapText(['历史记录', 'History']);
  const history = await tree();
  const hasHistory = !history.some(node => /暂无记录|No history/.test(node.text || ''));
  if (hasHistory && !['复制', '再次输入', '删除'].every(label => history.some(node => node['content-desc'] === label))) throw Error('Inline history controls are missing');
  await capture('history-actions');
  await tapText(['返回', 'Back']);
  await tapText(['返回', 'Back']);
  await capture('home-current');
  console.log('Shortcut selection and inline history controls verified');
} else if (command === 'residency') {
  const pid = (await adb('shell', 'pidof', 'com.phonemic')).trim();
  const cycles = [];
  for (const action of ['KEYCODE_BACK', 'KEYCODE_HOME', 'KEYCODE_BACK']) {
    await adb('shell', 'input', 'keyevent', action);
    await pause(450);
    const backgroundPid = (await adb('shell', 'pidof', 'com.phonemic')).trim();
    if (backgroundPid !== pid) throw Error('PhoneMIC process changed while leaving the UI');
    const services = await adb('shell', 'dumpsys', 'activity', 'services', 'com.phonemic');
    if (!services.includes('isForeground=true')) throw Error('Model residency service is not running');
    const started = Date.now();
    const launch = await adb('shell', 'am', 'start', '-W', '-n', 'com.phonemic/.MainActivity');
    const nodes = await tree();
    if (!nodes.some(node => node['resource-id'] === 'voice-button' && node.enabled === 'true')) throw Error('Cached model was not immediately available');
    cycles.push({action, pidPreserved: true, launchState: launch.match(/LaunchState:\s*(\w+)/)?.[1], readyCheckedMs: Date.now() - started});
  }
  const logs = await adb('logcat', '-d', '--pid', pid, '-s', 'PhoneMicModel:I', '*:S');
  const initializations = (logs.match(/Local model ready: initialization=/g) || []).length;
  if (initializations !== 1) throw Error(`Model initialized ${initializations} times in one process`);
  await fs.writeFile(path.join(root, 'artifacts/residency-check.json'), JSON.stringify({pid, initializations, cycles}, null, 2));
  await capture('home-current');
  console.log('Three background/return cycles reused the same process and one model initialization');
} else if (command === 'recording') {
  const receiverRunning = await new Promise(resolve => {
    const socket = net.createConnection({host: '127.0.0.1', port: 8765});
    socket.once('connect', () => { socket.destroy(); resolve(true); });
    socket.once('error', () => resolve(false));
    socket.setTimeout(1000, () => { socket.destroy(); resolve(false); });
  });
  if (receiverRunning) throw Error('Stop the local receiver before this UI-only recording check');
  await tapText(['设置', 'Settings']);
  await tapText(['点击', 'Tap']);
  await tapText(['返回', 'Back']);
  await capture('home-current');
  const idleNodes = await tree();
  const button = idleNodes.find(node => node['resource-id'] === 'voice-button');
  const bounds = button?.bounds.match(/\d+/g)?.map(Number);
  if (!bounds) throw Error('Voice button bounds are missing');
  const tapVoice = () => adb('shell', 'input', 'tap', String(Math.round((bounds[0] + bounds[2]) / 2)), String(Math.round((bounds[1] + bounds[3]) / 2)));
  await tapVoice();
  await pause(350);
  await capture('recording-feedback');
  await pause(1800);
  const microphone = await adb('shell', 'cmd', 'appops', 'get', 'com.phonemic', 'RECORD_AUDIO');
  if (!microphone.includes('(running)')) throw Error('Tap recording did not keep the microphone active');
  await capture('recording-toggle');
  await tapVoice();
  await pause(1200);
  const stoppedMicrophone = await adb('shell', 'cmd', 'appops', 'get', 'com.phonemic', 'RECORD_AUDIO');
  if (stoppedMicrophone.includes('(running)')) throw Error('Microphone did not stop');
  const stoppedNodes = await tree();
  if (stoppedNodes.some(node => node.selected === 'true')) throw Error('Tap recording did not finish');
  for (let attempt = 0; attempt < 30; attempt++) {
    const nodes = await tree();
    if (nodes.some(node => node['resource-id'] === 'voice-button' && node.enabled === 'true')) break;
    if (attempt === 29) throw Error('Final recognition did not complete');
    await pause(250);
  }
  await capture('home-current');
  await fs.writeFile(path.join(root, 'artifacts/button-check.json'), JSON.stringify({recordingStarted: true, recordingStopped: true, buttonBounds: button.bounds}, null, 2));
  console.log('Physical phone recording button starts and stops correctly');
} else {
  await capture('home-current');
  const nodes = await tree();
  console.log(nodes.filter(n => n.text || n['content-desc']).map(n => n.text || n['content-desc']).join('\n'));
}
