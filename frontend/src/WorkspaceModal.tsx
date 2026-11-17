import { useEffect, useRef, useState, type ReactNode } from 'react';

type WorkspaceModalProps = {
  title: string;
  description?: string;
  triggerLabel: string;
  triggerClassName?: string;
  children: ReactNode;
};

/** Shared roomy editor for dense workspace registers. */
export function WorkspaceModal({ title, description, triggerLabel, triggerClassName = 'workspace-editor-trigger', children }: WorkspaceModalProps) {
  const [open, setOpen] = useState(false);
  const dialogRef = useRef<HTMLDialogElement>(null);

  useEffect(() => {
    const dialog = dialogRef.current;
    if (open && dialog && !dialog.open) dialog.showModal();
    if (!open && dialog?.open) dialog.close();
  }, [open]);

  return <>
    <button type="button" className={triggerClassName} aria-haspopup="dialog" onClick={() => setOpen(true)}>{triggerLabel}</button>
    <dialog ref={dialogRef} className="workspace-editor-dialog" aria-label={title} onClose={() => setOpen(false)} onClick={event => { if (event.target === event.currentTarget) setOpen(false); }}>
      <header className="workspace-editor-dialog-header">
        <div><p className="eyebrow">WORKSPACE EDITOR</p><h2>{title}</h2>{description && <p>{description}</p>}</div>
        <button type="button" className="workspace-editor-dialog-close" aria-label={`Close ${title}`} onClick={() => setOpen(false)}>×</button>
      </header>
      <div className="workspace-editor-dialog-content">{children}</div>
    </dialog>
  </>;
}
