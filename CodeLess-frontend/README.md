# CodeLess-frontend

## 地址配置

复制 `.env.example` 为 `.env.local`，按环境填写以下地址：

| 变量 | 用途 | 本地示例 |
| --- | --- | --- |
| `VITE_API_BASE_URL` | 后端业务接口与代码生成 SSE | `/api` |
| `VITE_PREVIEW_BASE_URL` | 生成中应用的预览与可访问性检查，包含 `/static` | `http://localhost:8123/api/static` |
| `VITE_DEPLOY_BASE_URL` | 已部署作品的访问地址 | `http://localhost:9090` |

本地开发时，Vite 将同源的 `/api` 请求转发到 `http://localhost:8123`，并保留 `/api` 路径。生产环境使用 `/api` 时，需要由前端站点转发该路径。

后端对应使用 `APP_PREVIEW_BASE_URL`（自动截图与封面链接）和 `APP_DEPLOY_BASE_URL`（部署接口返回的链接）。前后端的预览地址应一致，部署地址也应一致；两类地址可以使用不同域名。后端未设置环境变量时使用本地默认值。

Vite 变量在启动或构建时读取，修改后需重新启动前端开发服务或重新构建；后端变量修改后需重启服务。

可视化编辑要求对话页和生成预览使用同一个主机名。本地可通过 `localhost:5173` 打开前端，预览仍由 `localhost:8123` 提供。

## 生成 OpenAPI 请求代码

启动 Spring Boot 后，在前端目录执行 `npm run openapi2ts`。命令默认读取 `VITE_API_BASE_URL` 下的 `/v3/api-docs`。使用同源路径时，脚本直接向本地后端 `http://localhost:8123` 请求文档，未配置时使用 `http://localhost:8123/api/v3/api-docs`。也可以通过 `OPENAPI_SCHEMA_URL` 指定完整的文档地址。

生成结果位于 `tmp/openapi/api/`，供核对接口和类型。项目运行时继续使用 `src/api/`。

## 头像上传

个人中心支持填写头像图片 URL，也支持上传 PNG、JPG/JPEG、WebP 图片（最多 5 MB）。上传后会立即保存当前用户头像，不需要再点击“保存修改”；昵称和简介仍通过“保存修改”提交。

后端默认将上传文件保存在 `./data/avatars`，并返回 `http://localhost:8123/api/user/avatar/{文件名}`。部署时应将 `APP_AVATAR_STORAGE_DIR` 设置为持久化目录，将 `APP_AVATAR_PUBLIC_BASE_URL` 设置为用户可访问的后端头像地址前缀（包含 `/api/user/avatar`）。上传目录不应放入代码仓库。

This template should help get you started developing with Vue 3 in Vite.

## Recommended IDE Setup

[VSCode](https://code.visualstudio.com/) + [Volar](https://marketplace.visualstudio.com/items?itemName=Vue.volar) (and disable Vetur).

## Type Support for `.vue` Imports in TS

TypeScript cannot handle type information for `.vue` imports by default, so we replace the `tsc` CLI with `vue-tsc` for type checking. In editors, we need [Volar](https://marketplace.visualstudio.com/items?itemName=Vue.volar) to make the TypeScript language service aware of `.vue` types.

## Customize configuration

See [Vite Configuration Reference](https://vite.dev/config/).

## Project Setup

```sh
npm install
```

### Compile and Hot-Reload for Development

```sh
npm run dev
```

### Type-Check, Compile and Minify for Production

```sh
npm run build
```

### Lint with [ESLint](https://eslint.org/)

```sh
npm run lint
```
