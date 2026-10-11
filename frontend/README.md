# ikun 秒杀 · 前端控制台

Vite + React 19 + TypeScript + Antd 6 + TanStack Query + Zustand + ECharts。

## 启动

```bash
npm install
npm run dev     # http://localhost:5173，/seckill 与 /api/ai 代理到 localhost:8081
```

要求后端已启动（`server.port=8081`），且库存已预热，否则下单返回 1003。

## 页面

| 路由 | 内容 |
|---|---|
| `/seckill` | 秒杀演示：库存余量轮询、下单、订单状态机轮询（QUEUED → SUCCESS/FAILED） |
| `/ops` | 运行指标分段总账、链路趋势采样图、库存对账（含 repair 模式） |
| `/monitor` | AI 事故中心：活跃事故（5s 轮询）+ 历史诊断 |
| `/monitor/:incidentId` | 诊断详情：结论/证据/建议 + ReAct 工具调用轨迹时间线 |

## 架构约定

- `src/features/*` 按业务域分包，域内自带 `api.ts / types.ts / hooks.ts / pages/`；
  域之间只通过 `src/lib` 与 `src/components` 共享，禁止互相 import——
  未来拆分模块或抽取 Admin 后台时，域可以整体搬走。
- 所有请求经 `src/lib/api/client.ts` 单点收口（Result 解包、鉴权头、错误翻译）。
- 后端 DTO 变更只改对应域的 `types.ts`。
- 登录/权限为预留壳子（`features/auth`），`enabled=false` 时游客直通。

## 部署

```bash
docker build -t seckill-frontend . 
docker run -p 8080:80 -e BACKEND_ORIGIN=http://host.docker.internal:8081 seckill-frontend
```

Nginx 反代后端，生产同域无跨页；接入 docker-compose 网络时把 `BACKEND_ORIGIN`
改成服务名（如 `http://app:8081`）。
