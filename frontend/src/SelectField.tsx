import { useEffect, useRef, useState, type KeyboardEvent as ReactKeyboardEvent } from 'react';

export type SelectOption = { value: string; label: string };

export function SelectField({ value, onChange, options, label, disabled = false }: {
  value: string; onChange: (value: string) => void; options: SelectOption[]; label: string; disabled?: boolean;
}) {
  const [open, setOpen] = useState(false);
  const [active, setActive] = useState(Math.max(0, options.findIndex(option => option.value === value)));
  const root = useRef<HTMLDivElement>(null);
  const trigger = useRef<HTMLButtonElement>(null);
  const id = label.toLowerCase().replace(/[^a-z0-9]+/g, '-');
  const selectedIndex = Math.max(0, options.findIndex(option => option.value === value));
  useEffect(() => {
    if (!open) return;
    const closeOutside = (event: PointerEvent) => { if (event.target instanceof Node && !root.current?.contains(event.target)) setOpen(false); };
    document.addEventListener('pointerdown', closeOutside);
    return () => document.removeEventListener('pointerdown', closeOutside);
  }, [open]);
  function show(index = selectedIndex) { setActive(index); setOpen(true); }
  function choose(index: number) { const option = options[index]; if (option) onChange(option.value); setOpen(false); requestAnimationFrame(() => trigger.current?.focus()); }
  function keyDown(event: ReactKeyboardEvent<HTMLDivElement>) {
    if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
      event.preventDefault();
      if (!open) { show(selectedIndex); return; }
      setActive(current => (current + (event.key === 'ArrowDown' ? 1 : -1) + options.length) % options.length);
    } else if (event.key === 'Home' || event.key === 'End') {
      event.preventDefault(); const index = event.key === 'Home' ? 0 : options.length - 1; setActive(index); if (!open) setOpen(true);
    } else if ((event.key === 'Enter' || event.key === ' ') && open) { event.preventDefault(); choose(active); }
    else if (event.key === 'Escape' && open) { event.preventDefault(); setOpen(false); trigger.current?.focus(); }
    else if (event.key === 'Tab' && open) setOpen(false);
    else if (!open && event.key.length === 1 && /^[a-z0-9]$/i.test(event.key)) {
      const index = options.findIndex(option => option.label.toLowerCase().startsWith(event.key.toLowerCase())); if (index >= 0) choose(index);
    }
  }
  return <div className={`custom-select${open ? ' is-open' : ''}`} ref={root} onKeyDown={keyDown}>
    <button ref={trigger} type="button" className="custom-select-trigger" role="combobox" aria-label={label} aria-haspopup="listbox" aria-expanded={open} aria-controls={`select-${id}`} aria-activedescendant={open ? `option-${id}-${active}` : undefined} disabled={disabled} onClick={() => open ? setOpen(false) : show()}>
      <span>{options.find(option => option.value === value)?.label || 'Select an option'}</span><svg viewBox="0 0 20 20" aria-hidden="true"><path d="m5 7.5 5 5 5-5" /></svg>
    </button>
    {open && <div className="custom-select-menu" id={`select-${id}`} role="listbox" aria-label={label}>
      {options.map((option, index) => <button id={`option-${id}-${index}`} key={option.value} type="button" role="option" tabIndex={-1} aria-selected={option.value === value} className={index === active ? 'is-active' : ''} onMouseDown={event => event.preventDefault()} onMouseEnter={() => setActive(index)} onClick={() => choose(index)}>{option.label}{option.value === value && <span aria-hidden="true">✓</span>}</button>)}
    </div>}
  </div>;
}
