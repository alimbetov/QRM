import React from 'react';
import ReactDOM from 'react-dom/client';
import LiveCatalog from './LiveCatalog';
import QrLanding from './QrLanding';
import BuyerFlow from './BuyerFlow';
import AuthCallback from './AuthCallback';
import AuthSignedOut from './AuthSignedOut';
import {tokenFromPath} from './qr-api';

const path=window.location.pathname;
const token=tokenFromPath(path);
const page=path==='/auth/callback' ? <AuthCallback/> :
           path==='/auth/signed-out' ? <AuthSignedOut/> :
           path==='/buy' ? <BuyerFlow/> :
           token ? <QrLanding token={token}/> : <LiveCatalog/>;
ReactDOM.createRoot(document.getElementById('root')!).render(<React.StrictMode>{page}</React.StrictMode>);
