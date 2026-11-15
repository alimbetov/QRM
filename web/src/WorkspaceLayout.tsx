import type {ReactNode} from 'react';
import './workspace.css';

export function WorkspaceLayout({children}: {children:ReactNode}) {
  const path=window.location.pathname;
  const sellerOn=import.meta.env.VITE_SELLER_WORKSPACE_ENABLED==='true';
  return <div className="workspace-layout">
    <header className="workspace-topbar">
      <div className="workspace-navigation">
        <a className="workspace-logo" href="/">ZAKAZ</a>
        <nav aria-label="Основная навигация">
          <a href="/" aria-current={path==='/'?'page':undefined}>Каталог</a>
          <a href="/buy" aria-current={path==='/buy'?'page':undefined}>Заявки и заказы</a>
          {sellerOn&&<a href="/seller" aria-current={path.startsWith('/seller')?'page':undefined}>
            Продавцу</a>}
        </nav>
      </div>
    </header>
    {children}
  </div>;
}
export function NotFound() {
  return <main className="workspace-not-found">
    <h1>Страница не найдена</h1>
    <p>Указанный адрес не принадлежит опубликованному разделу ZAKAZ.</p>
    <a href="/">Вернуться к каталогу</a>
  </main>;
}
