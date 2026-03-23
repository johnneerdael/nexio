import type { Config } from 'tailwindcss'

export default <Partial<Config>>{
  theme: {
    extend: {
      colors: {
        surface: '#0e0e0e',
        'surface-container-lowest': '#000000',
        'surface-container-low': '#131313',
        'surface-container': '#1a1a1a',
        'surface-variant': '#262626',
        'surface-bright': '#333333',
        primary: {
          DEFAULT: '#ba9eff',
          dim: '#8455ef',
        },
        secondary: '#53ddfc',
        tertiary: '#ff97b5',
        'on-surface': '#ffffff',
        'on-surface-variant': '#adaaaa',
        'outline-variant': '#494847',
      },
      fontFamily: {
        display: ['Manrope', 'sans-serif'],
        sans: ['Inter', 'sans-serif'],
      },
      borderRadius: {
        xl: '0.75rem',
      },
      boxShadow: {
        ambient: '0 40px 100px rgba(186, 158, 255, 0.06)',
      }
    }
  }
}
