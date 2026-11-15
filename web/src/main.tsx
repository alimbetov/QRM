import React from 'react';
import ReactDOM from 'react-dom/client';
import {BrowserRouter,Route,Routes,useParams} from 'react-router-dom';
import LiveCatalog from './LiveCatalog';
import QrLanding from './QrLanding';
import BuyerFlow from './BuyerFlow';
import AuthCallback from './AuthCallback';
import AuthSignedOut from './AuthSignedOut';
import SellerWorkspace from './SellerWorkspace';
import SellerAuthCallback from './SellerAuthCallback';
import SellerAuthSignedOut from './SellerAuthSignedOut';
import {WorkspaceLayout,NotFound} from './WorkspaceLayout';
import {qrTokenPattern} from './qr-api';

function QrRoute(){
  const {token}=useParams<{token:string}>();
  return token&&qrTokenPattern.test(token)?<QrLanding token={token}/>:<NotFound/>;
}
function WorkspaceRoutes(){
  return <Routes>
    <Route path="/auth/callback" element={<AuthCallback/>}/>
    <Route path="/auth/signed-out" element={<AuthSignedOut/>}/>
    <Route path="/seller/auth/callback" element={<SellerAuthCallback/>}/>
    <Route path="/seller/auth/signed-out" element={<SellerAuthSignedOut/>}/>
    <Route path="/" element={<WorkspaceLayout><LiveCatalog/></WorkspaceLayout>}/>
    <Route path="/q/:token" element={<WorkspaceLayout><QrRoute/></WorkspaceLayout>}/>
    <Route path="/buy" element={<WorkspaceLayout><BuyerFlow/></WorkspaceLayout>}/>
    <Route path="/seller" element={<WorkspaceLayout><SellerWorkspace/></WorkspaceLayout>}/>
    <Route path="*" element={<WorkspaceLayout><NotFound/></WorkspaceLayout>}/>
  </Routes>;
}
ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode><BrowserRouter><WorkspaceRoutes/></BrowserRouter></React.StrictMode>
);
