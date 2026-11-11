import { useEffect } from 'react';

export function Toast({message,kind='success',onDismiss}:{message:string;kind?:'success'|'error';onDismiss:()=>void}){
  useEffect(()=>{if(!message)return;const timer=window.setTimeout(onDismiss,6000);return()=>window.clearTimeout(timer)},[message,onDismiss]);
  if(!message)return null;
  return <div className={`app-toast app-toast-${kind}`} role={kind==='error'?'alert':'status'} aria-live={kind==='error'?'assertive':'polite'}><span aria-hidden="true">{kind==='error'?'!':'✓'}</span><p>{message}</p><button type="button" aria-label="Dismiss notification" onClick={onDismiss}>×</button></div>;
}
