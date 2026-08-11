import type { ThemeConfig } from 'ant-design-vue/es/config-provider/context'

export const appTheme = {
  token: {
    colorPrimary: '#2563eb',
    colorInfo: '#2563eb',
    colorSuccess: '#10b981',
    colorWarning: '#f59e0b',
    colorError: '#ef4444',
    colorText: '#374151',
    colorTextHeading: '#111827',
    colorTextDescription: '#5b6b82',
    colorTextPlaceholder: '#5b6b82',
    colorSuccessText: '#047857',
    colorWarningText: '#92400e',
    colorErrorText: '#b91c1c',
    colorBorder: '#e7edf6',
    colorBgLayout: '#f5f7fa',
    borderRadius: 8,
    fontSize: 14,
    fontFamily: 'Inter, "PingFang SC", "Microsoft YaHei", system-ui, sans-serif',
  },
  components: {
    Menu: {
      darkItemBg: '#163b83',
      darkSubMenuItemBg: '#163b83',
      darkItemColor: '#eff6ff',
      darkItemHoverColor: '#ffffff',
      darkItemHoverBg: 'rgba(255, 255, 255, 0.11)',
      darkItemSelectedColor: '#ffffff',
      darkItemSelectedBg: '#2563eb',
      itemHeight: 44,
      itemBorderRadius: 8,
      itemMarginInline: 0,
    },
  },
} as ThemeConfig
