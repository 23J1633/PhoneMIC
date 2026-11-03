import { StyleSheet } from 'react-native';
export const s = StyleSheet.create({
  row: { flexDirection: 'row', alignItems: 'center' },
  between: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
  },
  header: {
    height: 60,
    paddingHorizontal: 14,
    flexDirection: 'row',
    alignItems: 'center',
    gap: 4,
  },
  content: { paddingHorizontal: 28, paddingTop: 12, paddingBottom: 36 },
  setting: {
    minHeight: 64,
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    borderBottomWidth: StyleSheet.hairlineWidth,
    gap: 18,
  },
  input: {
    height: 48,
    fontSize: 15,
    borderBottomWidth: StyleSheet.hairlineWidth,
    paddingHorizontal: 0,
  },
  notice: {
    position: 'absolute',
    bottom: 28,
    left: 28,
    right: 28,
    padding: 14,
    borderRadius: 12,
  },
});
