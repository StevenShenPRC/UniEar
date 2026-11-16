import { defineConfig } from 'vitepress'

const repo = 'https://github.com/StevenShenPRC/UniEar'

export default defineConfig({
  title: 'UniEar',
  description: 'Cross-brand Bluetooth headphone controls',
  base: process.env.DOCS_BASE ?? '/',
  locales: {
    root: {
      label: 'English',
      lang: 'en',
      themeConfig: {
        nav: [{ text: 'Guide', link: '/guide' }, { text: 'Releases', link: `${repo}/releases` }],
        sidebar: [{ text: 'Documentation', items: [
          { text: 'Getting started', link: '/guide' },
          { text: 'Troubleshooting', link: '/troubleshooting' },
          { text: 'Development and releases', link: '/development' },
        ] }],
      },
    },
    zh: {
      label: '简体中文',
      lang: 'zh-CN',
      description: '跨品牌蓝牙耳机控制',
      themeConfig: {
        nav: [{ text: '使用指南', link: '/zh/guide' }, { text: '版本发布', link: `${repo}/releases` }],
        sidebar: [{ text: '文档', items: [
          { text: '快速开始', link: '/zh/guide' },
          { text: '问题排查', link: '/zh/troubleshooting' },
          { text: '开发与发布', link: '/zh/development' },
        ] }],
        docFooter: { prev: '上一页', next: '下一页' },
        outline: { label: '本页目录' },
        returnToTopLabel: '返回顶部',
        sidebarMenuLabel: '菜单',
        darkModeSwitchLabel: '主题',
        langMenuLabel: '切换语言',
      },
    },
  },
  themeConfig: {
    search: {
      provider: 'local',
      options: { locales: { zh: { translations: {
        button: { buttonText: '搜索', buttonAriaLabel: '搜索文档' },
        modal: { noResultsText: '未找到结果', resetButtonTitle: '清除搜索', footer: {
          selectText: '选择', navigateText: '切换', closeText: '关闭',
        } },
      } } } },
    },
    socialLinks: [{ icon: 'github', link: repo }],
  },
})
