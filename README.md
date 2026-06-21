# Halo 文章访问控制插件

English: Halo Post Access Control

为 Halo 文章增加前台访问权限控制。

## 简介

Halo Post Access Control 会在 Console 的文章列表操作菜单中新增“访问权限”，支持：

- 公开：任何人可见
- 普通：登录用户可见
- 私有：仅文章作者可见

权限值保存在文章 `metadata.annotations` 中，键名为
`permission.haoshenqi.com/access`。未设置时默认普通（登录用户可见）。


## 开发方式

codex gpt-5.5

prompt:

```
# Halo 文章访问权限控制插件

此插件在文章列表操作菜单新增“访问权限”选项，支持：

1. 公开（任何人可见）
2. 普通（登录可见）
3. 私有（仅自己可见）

前台访问拦截规则：

- 公开：任何人可见
- 普通：仅登录用户可见
- 私有：仅文章作者可见. 帮我完成这个插件的开发
```

## 开发环境

- Java 21+
- Node.js 18+
- pnpm

## 开发

```bash
# 启用插件
./gradlew haloServer
# 开发前端
cd ui
pnpm install
pnpm dev
```

## 构建

```bash
./gradlew build
```

构建完成后，可以在 `build/libs` 目录找到插件 jar 文件。

## 许可证

[GPL-3.0](./LICENSE) © haoshenqi 
