import { CopilotKitProvider, CopilotChat } from '@copilotkit/react-core/v2'
import { HttpAgent } from '@ag-ui/client'
import '@copilotkit/react-ui/v2/styles.css'

// 直连 agui-server 的 AG-UI endpoint（经 vite proxy /agui → 8095）。
// HttpAgent 继承 AbstractAgent，作为 selfManagedAgents 传给 CopilotKitProvider，无需 Node runtime。
// 注意：必须显式传 fetch —— HttpAgent 默认实现调用 fetch 时丢失 window 上下文，浏览器会抛
// "TypeError: Illegal invocation"。用箭头函数确保 fetch 始终以 window 为 this 调用。
const agent = new HttpAgent({
  url: '/agui',
  fetch: (...args) => window.fetch(...args),
})

export default function App() {
  return (
    <CopilotKitProvider selfManagedAgents={{ default: agent }}>
      <div style={{ height: '100vh', display: 'flex', flexDirection: 'column', maxWidth: 820, margin: '0 auto' }}>
        <CopilotChat agentId="default" />
      </div>
    </CopilotKitProvider>
  )
}
