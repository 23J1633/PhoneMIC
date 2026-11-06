import React, { useCallback, useEffect, useRef, useState } from 'react';
import {
  ActivityIndicator,
  Alert,
  Keyboard,
  Pressable,
  ScrollView,
  Text,
  TextInput,
  View,
} from 'react-native';
import { ActionButton, Icon, IconButton, Options } from './components';
import { Native } from './native';
import type { Palette } from './theme';
import type { BluetoothComputer, Computer } from './types';
import type { PhoneMicController } from './usePhoneMic';
import type { TextKey } from './i18n';
import { s } from './uiStyles';

function connectionError(failure: unknown, fallback: TextKey): TextKey {
  switch ((failure as { code?: string })?.code) {
    case 'LINKED':
      return 'receiverLinked';
    case 'ADDRESS':
      return 'invalidAddress';
    case 'CERTIFICATE':
      return 'certificateFailed';
    case 'UNREACHABLE':
      return 'lanUnreachable';
    default:
      return fallback;
  }
}

export function DevicesScreen({
  c,
  p,
  close,
}: {
  c: PhoneMicController;
  p: Palette;
  close: () => void;
}) {
  const [computers, setComputers] = useState<Computer[]>([]);
  const [bluetooth, setBluetooth] = useState<BluetoothComputer[]>([]);
  const [scanning, setScanning] = useState(false);
  const [connecting, setConnecting] = useState<string | null>(null);
  const [manual, setManual] = useState(false);
  const [host, setHost] = useState('');
  const [error, setError] = useState<TextKey | null>(null);
  const alive = useRef(true);
  const generation = useRef(0);
  const transport = c.settings.transport;
  const permission = c.ensureBluetoothPermission;
  useEffect(
    () => () => {
      alive.current = false;
      generation.current++;
    },
    [],
  );
  const scan = useCallback(async () => {
    const current = ++generation.current;
    setScanning(true);
    setError(null);
    try {
      if (transport === 'lan') {
        const found = await Native.discover();
        if (alive.current && current === generation.current)
          setComputers(found);
      } else if (await permission()) {
        const found = await Native.bluetoothDevices();
        if (alive.current && current === generation.current)
          setBluetooth(found);
      }
    } catch {
      if (alive.current && current === generation.current)
        setError(transport === 'lan' ? 'scanFailed' : 'bluetoothFailed');
    } finally {
      if (alive.current && current === generation.current) setScanning(false);
    }
  }, [transport, permission]);
  useEffect(() => {
    void scan();
  }, [scan]);
  const connect = async (computer: Computer | BluetoothComputer) => {
    if (connecting) return;
    setConnecting(computer.id);
    setError(null);
    try {
      if (transport === 'lan') await c.pairComputer(computer as Computer, '');
      else await c.selectBluetooth(computer as BluetoothComputer);
      if (alive.current) close();
    } catch (failure) {
      if (alive.current)
        setError(
          connectionError(
            failure,
            transport === 'lan' ? 'pairFailed' : 'bluetoothFailed',
          ),
        );
    } finally {
      if (alive.current) setConnecting(null);
    }
  };
  const find = async () => {
    if (!host.trim() || scanning) return;
    const current = ++generation.current;
    Keyboard.dismiss();
    setScanning(true);
    setError(null);
    try {
      const computer = await Native.probe(host.trim());
      if (alive.current && current === generation.current)
        await connect(computer);
    } catch (failure) {
      if (alive.current && current === generation.current)
        setError(connectionError(failure, 'scanFailed'));
    } finally {
      if (alive.current && current === generation.current) setScanning(false);
    }
  };
  const known = transport === 'lan' ? c.computer : c.bluetoothComputer;
  const available = (transport === 'lan' ? computers : bluetooth).filter(
    item => item.id !== known?.id,
  );
  const forget = () =>
    Alert.alert(c.t('forget'), c.t('forgetConfirm'), [
      { text: c.t('cancel'), style: 'cancel' },
      { text: c.t('forget'), style: 'destructive', onPress: c.forgetComputer },
    ]);
  return (
    <ScrollView
      keyboardShouldPersistTaps="handled"
      contentContainerStyle={s.content}
    >
      <View
        style={{ alignItems: 'flex-start', marginLeft: -9, marginBottom: 22 }}
      >
        <Options
          p={p}
          value={transport}
          options={[
            { value: 'lan', label: c.t('lan') },
            { value: 'bluetooth', label: c.t('bluetooth') },
          ]}
          onChange={value => {
            setError(null);
            c.updateSettings({ transport: value });
          }}
        />
      </View>
      {known && (
        <View style={[s.setting, { borderBottomColor: p.line }]}>
          <View style={{ flex: 1 }}>
            <Text style={{ fontSize: 14, color: p.ink }}>{known.name}</Text>
            <Text style={{ fontSize: 11, color: p.secondary, marginTop: 6 }}>
              {c.t(
                c.connection === 'connected'
                  ? 'connected'
                  : c.connection === 'connecting'
                  ? 'connecting'
                  : 'disconnected',
              )}
              {c.latency != null ? ` · ${c.latency} ms` : ''}
            </Text>
          </View>
          <IconButton
            p={p}
            name="more"
            label={c.t('forget')}
            onPress={forget}
          />
        </View>
      )}
      {!known && (
        <Text
          style={{
            color: p.secondary,
            fontSize: 12,
            lineHeight: 21,
            marginBottom: 18,
          }}
        >
          {c.t(transport === 'lan' ? 'desktopStep' : 'bluetoothNote')}
        </Text>
      )}
      <View style={[s.between, { minHeight: 58 }]}>
        <Text style={{ fontSize: 12, color: p.faint }}>
          {scanning
            ? c.t('scanning')
            : available.length === 0 && !known
            ? c.t('noComputers')
            : ''}
        </Text>
        {scanning ? (
          <ActivityIndicator size="small" color={p.faint} />
        ) : (
          <IconButton
            p={p}
            name="refresh"
            label={c.t('refresh')}
            onPress={() => {
              void scan();
            }}
          />
        )}
      </View>
      {transport === 'lan' &&
        !scanning &&
        available.length === 0 &&
        c.connection !== 'connected' && (
          <Text
            style={{
              color: p.secondary,
              fontSize: 12,
              lineHeight: 21,
              marginBottom: 12,
            }}
          >
            {c.t('noComputersHint')}
          </Text>
        )}
      {available.map(computer => (
        <Pressable
          key={computer.id}
          accessibilityRole="button"
          disabled={!!connecting}
          style={[s.setting, { borderBottomColor: p.line }]}
          onPress={() => {
            void connect(computer);
          }}
        >
          <Text style={{ fontSize: 14, color: p.ink, flex: 1 }}>
            {computer.name}
          </Text>
          {connecting === computer.id ? (
            <ActivityIndicator size="small" color={p.faint} />
          ) : (
            <Icon name="arrow" size={13} color={p.faint} />
          )}
        </Pressable>
      ))}
      {transport === 'lan' ? (
        <>
          <Pressable
            accessibilityRole="button"
            onPress={() => setManual(!manual)}
            style={{ paddingVertical: 22 }}
          >
            <Text style={{ color: p.secondary, fontSize: 12 }}>
              {c.t('manual')}
            </Text>
          </Pressable>
          {manual && (
            <View style={[s.row, { gap: 18 }]}>
              <TextInput
                value={host}
                onChangeText={setHost}
                accessibilityLabel={c.t('ipPlaceholder')}
                placeholder={c.t('ipPlaceholder')}
                placeholderTextColor={p.faint}
                autoCapitalize="none"
                autoCorrect={false}
                keyboardType="url"
                onSubmitEditing={() => {
                  void find();
                }}
                style={[
                  s.input,
                  { flex: 1, color: p.ink, borderBottomColor: p.line },
                ]}
              />
              <Pressable
                accessibilityRole="button"
                disabled={scanning || !!connecting || !host.trim()}
                onPress={() => {
                  void find();
                }}
                style={{ paddingVertical: 16 }}
              >
                <Text style={{ fontSize: 13, color: p.accent }}>
                  {c.t('find')}
                </Text>
              </Pressable>
            </View>
          )}
        </>
      ) : (
        <View style={{ marginTop: 22 }}>
          <ActionButton
            p={p}
            label={c.t('discoverable')}
            onPress={() => {
              void (async () => {
                try {
                  if (await permission()) {
                    await Native.bluetoothDevices();
                    await Native.makeDiscoverable();
                  }
                } catch {
                  setError('bluetoothFailed');
                }
              })();
            }}
          />
          <Pressable
            accessibilityRole="button"
            onPress={() => Native.openBluetoothSettings()}
            style={{ paddingVertical: 22, alignItems: 'center' }}
          >
            <Text style={{ fontSize: 12, color: p.secondary }}>
              {c.t('bluetoothSettings')}
            </Text>
          </Pressable>
        </View>
      )}
      {error && (
        <Text
          accessibilityRole="alert"
          style={{
            color: p.danger,
            fontSize: 12,
            lineHeight: 21,
            marginTop: 18,
          }}
        >
          {c.t(error)}
        </Text>
      )}
    </ScrollView>
  );
}
