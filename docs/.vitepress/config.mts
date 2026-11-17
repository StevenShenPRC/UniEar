import { defineConfig } from 'vitepress'

const repo = 'https://github.com/StevenShenPRC/UniEar'

export default defineConfig({
  title: 'UniEar',
  description: 'Cross-brand Bluetooth headphone controls',
  base: process.env.DOCS_BASE ?? '/',
  locales: {
    ar: {
      label: "العربية",
      lang: 'ar',
      dir: 'rtl',
      description: "بدّل وضع الاستماع، واعرض مستوى البطارية، واضبط الطرز المدعومة.",
      themeConfig: {
        nav: [{ text: "الدليل", link: '/ar/guide' }, { text: "الإصدارات", link: `${repo}/releases` }],
        sidebar: [{ text: "الدليل", items: [
          { text: "البدء", link: '/ar/guide' },
          { text: "استكشاف الأخطاء وإصلاحها", link: '/ar/troubleshooting' },
          { text: "التطوير والإصدارات", link: '/ar/development' },
        ] }],
        docFooter: { prev: "السابق", next: "التالي" },
        outline: { label: "على هذه الصفحة" },
        sidebarMenuLabel: "القائمة",
        langMenuLabel: "اختيار اللغة",
      },
    },
    es: {
      label: "Español",
      lang: 'es',
      description: "Cambia de modo, consulta la batería y configura los modelos compatibles.",
      themeConfig: {
        nav: [{ text: "Guía", link: '/es/guide' }, { text: "Versiones", link: `${repo}/releases` }],
        sidebar: [{ text: "Guía", items: [
          { text: "Primeros pasos", link: '/es/guide' },
          { text: "Solución de problemas", link: '/es/troubleshooting' },
          { text: "Desarrollo y versiones", link: '/es/development' },
        ] }],
        docFooter: { prev: "Anterior", next: "Siguiente" },
        outline: { label: "En esta página" },
        sidebarMenuLabel: "Menú",
        langMenuLabel: "Cambiar idioma",
      },
    },
    fr: {
      label: "Français",
      lang: 'fr',
      description: "Changez de mode d’écoute, vérifiez la batterie et configurez les modèles compatibles.",
      themeConfig: {
        nav: [{ text: "Guide", link: '/fr/guide' }, { text: "Versions", link: `${repo}/releases` }],
        sidebar: [{ text: "Guide", items: [
          { text: "Premiers pas", link: '/fr/guide' },
          { text: "Dépannage", link: '/fr/troubleshooting' },
          { text: "Développement et versions", link: '/fr/development' },
        ] }],
        docFooter: { prev: "Précédent", next: "Suivant" },
        outline: { label: "Sur cette page" },
        sidebarMenuLabel: "Menu",
        langMenuLabel: "Changer de langue",
      },
    },
    ru: {
      label: "Русский",
      lang: 'ru',
      description: "Меняйте режим прослушивания, проверяйте заряд и настраивайте совместимые модели.",
      themeConfig: {
        nav: [{ text: "Руководство", link: '/ru/guide' }, { text: "Выпуски", link: `${repo}/releases` }],
        sidebar: [{ text: "Руководство", items: [
          { text: "Начало работы", link: '/ru/guide' },
          { text: "Устранение неполадок", link: '/ru/troubleshooting' },
          { text: "Разработка и выпуски", link: '/ru/development' },
        ] }],
        docFooter: { prev: "Назад", next: "Далее" },
        outline: { label: "На этой странице" },
        sidebarMenuLabel: "Меню",
        langMenuLabel: "Сменить язык",
      },
    },
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
      options: { locales: {
        ar: { translations: { button: { buttonText: "بحث", buttonAriaLabel: "بحث" }, modal: { noResultsText: "لا توجد نتائج" } } },
        es: { translations: { button: { buttonText: "Buscar", buttonAriaLabel: "Buscar" }, modal: { noResultsText: "Sin resultados" } } },
        fr: { translations: { button: { buttonText: "Rechercher", buttonAriaLabel: "Rechercher" }, modal: { noResultsText: "Aucun résultat" } } },
        ru: { translations: { button: { buttonText: "Поиск", buttonAriaLabel: "Поиск" }, modal: { noResultsText: "Ничего не найдено" } } },
        zh: { translations: {
        button: { buttonText: '搜索', buttonAriaLabel: '搜索文档' },
        modal: { noResultsText: '未找到结果', resetButtonTitle: '清除搜索', footer: {
          selectText: '选择', navigateText: '切换', closeText: '关闭',
        } },
      } } } },
    },
    socialLinks: [{ icon: 'github', link: repo }],
  },
})
