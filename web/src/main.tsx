import React from 'react';
import ReactDOM from 'react-dom/client';
import App from './App';
import QrLanding from './QrLanding';
import {tokenFromPath} from './qr-api';
ReactDOM.createRoot(document.getElementById('root')!).render(<React.StrictMode>{tokenFromPath(window.location.pathname) ? <QrLanding token={tokenFromPath(window.location.pathname)!}/> : <App/>}</React.StrictMode>);
