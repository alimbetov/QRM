import React from 'react';
import ReactDOM from 'react-dom/client';
import LiveCatalog from './LiveCatalog';
import QrLanding from './QrLanding';
import {tokenFromPath} from './qr-api';
ReactDOM.createRoot(document.getElementById('root')!).render(<React.StrictMode>{tokenFromPath(window.location.pathname) ? <QrLanding token={tokenFromPath(window.location.pathname)!}/> : <LiveCatalog/>}</React.StrictMode>);
