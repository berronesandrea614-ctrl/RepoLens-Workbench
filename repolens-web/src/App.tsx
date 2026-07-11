import { useMemo, useState } from 'react';
import { ConfigProvider, Layout, Menu, Tag, theme, Typography } from 'antd';
import type { MenuProps } from 'antd';
import { API_BASE_URL, getCurrentUserId } from './api/request';
import RepoPage from './pages/RepoPage';
import TaskPage from './pages/TaskPage';
import ChatPage from './pages/ChatPage';
import RagSearchPage from './pages/RagSearchPage';
import ToolInvokePage from './pages/ToolInvokePage';

const { Header, Sider, Content } = Layout;

type PageKey = 'repos' | 'tasks' | 'chat' | 'rag' | 'tools';

const menuItems: MenuProps['items'] = [
  { key: 'repos', label: '仓库管理' },
  { key: 'tasks', label: '索引任务' },
  { key: 'chat', label: '代码问答' },
  { key: 'rag', label: 'RAG 检索' },
  { key: 'tools', label: '工具调试' }
];

function App() {
  const [pageKey, setPageKey] = useState<PageKey>('repos');
  const currentUserId = getCurrentUserId();

  const page = useMemo(() => {
    switch (pageKey) {
      case 'tasks':
        return <TaskPage />;
      case 'chat':
        return <ChatPage />;
      case 'rag':
        return <RagSearchPage />;
      case 'tools':
        return <ToolInvokePage />;
      default:
        return <RepoPage />;
    }
  }, [pageKey]);

  return (
    <ConfigProvider
      theme={{
        algorithm: theme.defaultAlgorithm,
        token: {
          colorPrimary: '#2563eb',
          borderRadius: 6,
          fontFamily:
            '-apple-system, BlinkMacSystemFont, "Segoe UI", "PingFang SC", "Microsoft YaHei", sans-serif'
        }
      }}
    >
      <Layout className="app-shell">
        <Sider width={220} theme="light" className="app-sider">
          <div className="sider-title">RepoLens</div>
          <Menu
            mode="inline"
            selectedKeys={[pageKey]}
            items={menuItems}
            onClick={(item) => setPageKey(item.key as PageKey)}
          />
        </Sider>
        <Layout>
          <Header className="app-header">
            <Typography.Title level={4} className="app-title">
              代码仓库智能理解 · Console
            </Typography.Title>
            <div className="header-meta">
              <Tag color="blue">Backend: {API_BASE_URL}</Tag>
              <Tag bordered={false} color="processing">
                User #{currentUserId}
              </Tag>
            </div>
          </Header>
          <Content className="app-content">{page}</Content>
        </Layout>
      </Layout>
    </ConfigProvider>
  );
}

export default App;
