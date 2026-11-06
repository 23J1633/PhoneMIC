import type { ThemeMode } from './types';
import { useColorScheme } from 'react-native';
const light = {
  dark: false,
  background: '#FFFFFF',
  surface: '#FFFFFF',
  raised: '#FFFFFF',
  ink: '#202020',
  secondary: '#828282',
  faint: '#B6B6B6',
  line: '#EAEAEA',
  accent: '#D5663D',
  accentSoft: '#FBF0EB',
  glow: '#FFFFFF',
  buttonEdge: '#E1E1E1',
  buttonOuter: '#F2F2F2',
  danger: '#B86A5E',
  connectionOnline: '#429568',
  connectionOffline: '#D35F59',
  shadow: '#2A2A2A',
};
const dark: typeof light = {
  dark: true,
  background: '#111111',
  surface: '#171717',
  raised: '#222222',
  ink: '#F3F3F3',
  secondary: '#929292',
  faint: '#5F5F5F',
  line: '#2C2C2C',
  accent: '#E7815B',
  accentSoft: '#34251E',
  glow: '#F2F2F2',
  buttonEdge: '#3A3A3A',
  buttonOuter: '#222222',
  danger: '#E5A392',
  connectionOnline: '#6BC291',
  connectionOffline: '#EF827A',
  shadow: '#000000',
};
export type Palette = typeof light;
export function usePalette(mode: ThemeMode): Palette {
  const system = useColorScheme();
  return mode === 'dark' || (mode === 'system' && system === 'dark')
    ? dark
    : light;
}
