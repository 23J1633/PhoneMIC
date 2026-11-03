import {createHash} from 'node:crypto';
import {createReadStream, createWriteStream} from 'node:fs';
import {mkdir, rename, rm, stat, writeFile} from 'node:fs/promises';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import {Readable, Transform} from 'node:stream';
import {pipeline} from 'node:stream/promises';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const model = 'sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25';
const base = `https://huggingface.co/csukuangfj2/${model}/resolve/main`;
const files = [
  ['conv_frontend.onnx', 'd22dc4423e0940e49884e903d2ea2f7e5567c14fc1aed97e4e26d6b8f208ef9e'],
  ['encoder.int8.onnx', '60748d3e6744a57c9c91e1b17424a6c2990567e8adceb0783940c03ed98fa9d9'],
  ['decoder.int8.onnx', '4f6885be5959ae26af3089d38ee7972c5fafbeeb1cf8d5e76eab6d8b61ca5771'],
  ['tokenizer/merges.txt', '8831e4f1a044471340f7c0a83d7bd71306a5b867e95fd870f74d0c5308a904d5'],
  ['tokenizer/tokenizer_config.json', '4942d005604266809309cabc9f4e9cb89ce855d59b14681fdc0e1cc62ea26c4c'],
  ['tokenizer/vocab.json', 'ca10d7e9fb3ed18575dd1e277a2579c16d108e32f27439684afa0e10b1440910'],
];
async function checksum(file) {
  const hash = createHash('sha256');
  for await (const bytes of createReadStream(file)) hash.update(bytes);
  return hash.digest('hex');
}
async function fetchFile(url, destination, expected) {
  await mkdir(path.dirname(destination), {recursive: true});
  try {
    const actual = await checksum(destination);
    if (expected && actual === expected) { console.log(`Cached ${path.basename(destination)}`); return actual; }
  } catch {}
  if (path.basename(destination) === 'decoder.int8.onnx') {
    const total = 755914231;
    const step = 16 * 1024 * 1024;
    const parts = Math.ceil(total / step);
    const cache = path.join(root, '.cache/qwen-download');
    await mkdir(cache, {recursive: true});
    let cursor = 0;
    const filenames = Array.from({length: parts}, (_, i) => path.join(cache, `${expected}.${i}`));
    await Promise.all(Array.from({length: 6}, async () => {
      while (cursor < parts) {
        const i = cursor++;
        const first = i * step;
        const last = Math.min(total, first + step) - 1;
        try { if ((await stat(filenames[i])).size === last - first + 1) continue; } catch {}
        for (let retry = 0; retry < 4; retry++) {
          try {
            const response = await fetch(`${url}&slice=${i}`, {headers: {Range: `bytes=${first}-${last}`}, signal: AbortSignal.timeout(90000)});
            if (response.status !== 206 || response.headers.get('content-range') !== `bytes ${first}-${last}/${total}`) {
              await response.body?.cancel(); throw Error(`Invalid model range ${i}: ${response.status}`);
            }
            await pipeline(Readable.fromWeb(response.body), createWriteStream(filenames[i] + '.pending'));
            if ((await stat(filenames[i] + '.pending')).size !== last - first + 1) throw Error('Incomplete model part');
            await rename(filenames[i] + '.pending', filenames[i]);
            console.log(`Qwen decoder ${i + 1}/${parts}`);
            break;
          } catch (error) { if (retry === 3) throw error; }
        }
      }
    }));
    async function* chunks() { for (const filename of filenames) { for await (const chunk of createReadStream(filename)) yield chunk; } }
    await pipeline(Readable.from(chunks()), createWriteStream(destination + '.pending'));
    if (await checksum(destination + '.pending') !== expected) throw Error('Qwen decoder checksum mismatch');
    await rename(destination + '.pending', destination);
    console.log(`Downloaded ${path.basename(destination)}: ${expected}`);
    return expected;
  }
  for (let attempt = 0; attempt < 3; attempt++) {
    try {
      const response = await fetch(url, {signal: AbortSignal.timeout(180000)});
      if (!response.ok || !response.body) throw Error(`Download failed: ${response.status}`);
      const hash = createHash('sha256');
      const progress = new Transform({transform(chunk, encoding, done) { hash.update(chunk); done(null, chunk); }});
      await pipeline(Readable.fromWeb(response.body), progress, createWriteStream(destination + '.pending'));
      const actual = hash.digest('hex');
      if (expected && actual !== expected) throw Error(`Checksum mismatch: ${path.basename(destination)}`);
      await rename(destination + '.pending', destination);
      console.log(`Downloaded ${path.basename(destination)}: ${actual}`);
      return actual;
    } catch (error) { if (attempt === 2) throw error; }
  }
}
const assets = path.join(root, 'android/app/src/main/assets/model-qwen');
const manifest = await Promise.all(files.map(async ([name, sha256]) => ({
  name, url: `${base}/${name}?download=true`, sha256: await fetchFile(`${base}/${name}?download=true`, path.join(assets, name), sha256),
})));
const aarURL = 'https://github.com/k2-fsa/sherpa-onnx/releases/download/v2.0.1/sherpa-onnx-2.0.1.aar';
const aarSHA = 'a8d09d1d32f3eb8520482c6d3d43fae674681c5b050385967c70a3c2c9bf0ee7';
await fetchFile(aarURL, path.join(root, 'android/app/libs/sherpa-onnx-2.0.1.aar'), aarSHA);
const vadURL = 'https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx';
const vadSHA = '9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6';
await fetchFile(vadURL, path.join(root, 'android/app/src/main/assets/model-vad/silero_vad.onnx'), vadSHA);
const speakerURL = 'https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models/3dspeaker_speech_campplus_sv_zh_en_16k-common_advanced.onnx';
const speakerSHA = 'aa3cfc16963a10586a9393f5035d6d6b57e98d358b347f80c2a30bf4f00ceba2';
await fetchFile(speakerURL, path.join(root, 'android/app/src/main/assets/model-speaker/campplus.onnx'), speakerSHA);
await writeFile(path.join(assets, 'model.version'), `${model}:int8-v1\n`);
for (const obsolete of ['model-zh', 'model-en', 'model-auto']) {
  const directory = path.resolve(root, 'android/app/src/main/assets', obsolete);
  if (!directory.startsWith(root + path.sep)) throw Error('Model cleanup outside project');
  await rm(directory, {recursive: true, force: true});
}
await mkdir(path.join(root, 'artifacts'), {recursive: true});
await writeFile(path.join(root, 'artifacts/models.json'), JSON.stringify({model, languages: ['zh', 'en'], automatic: true, license: 'Apache-2.0', files: manifest, vad: {url: vadURL, sha256: vadSHA}, speaker: {model: 'CAM++ zh-en advanced', url: speakerURL, sha256: speakerSHA}, runtime: {version: '2.0.1', url: aarURL, sha256: aarSHA}}, null, 2) + '\n');
if (process.argv.includes('--fixtures')) {
  await fetchFile('https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20/resolve/main/test_wavs/1.wav?download=true', path.join(root, '.cache/mixed-natural.wav'), '8bfb42c963e623ebab31b81ff4404867d07d3102507c87ac14577c4c61663b8c');
}
