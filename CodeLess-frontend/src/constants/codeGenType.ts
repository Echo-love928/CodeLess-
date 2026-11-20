export enum CodeGenTypeEnum {
  HTML = 'html',
  MULTI_FILE = 'multi_file',
  VUE_PROJECT = 'vue_project',
}

export const CODE_GEN_TYPE_CONFIG = {
  [CodeGenTypeEnum.HTML]: { label: '原生 HTML 模式', value: CodeGenTypeEnum.HTML },
  [CodeGenTypeEnum.MULTI_FILE]: { label: '原生多文件模式', value: CodeGenTypeEnum.MULTI_FILE },
  [CodeGenTypeEnum.VUE_PROJECT]: { label: 'Vue 项目模式', value: CodeGenTypeEnum.VUE_PROJECT },
} as const

export const CODE_GEN_TYPE_OPTIONS = Object.values(CODE_GEN_TYPE_CONFIG)

export const getCodeGenTypeLabel = (value?: string): string =>
  CODE_GEN_TYPE_OPTIONS.find((option) => option.value === value)?.label ?? value ?? '-'
