import { useEffect, useMemo, useRef, useState, type FormEvent } from 'react';

type OrgCompany = { id: string; companyCode: string; legalName: string; ownerName: string | null };
type Employee = { id: string; firstName: string; lastName: string; employeeStatus?: string; photoDataUrl?: string | null };
type OrgNode = { id: string; companyId: string; parentId: string | null; nodeType: string; name: string; title: string | null; employeeId: string | null; employeeName: string | null; photoDataUrl: string | null; employeePhotoDataUrl: string | null; sortOrder: number; active: boolean };
type Draft = { id?: string; parentId: string | null; nodeType: string; name: string; title: string; employeeId: string; photoDataUrl: string; sortOrder: number; active: boolean };
type View = 'chart' | 'table';

const typeNames: Record<string, string> = { OWNER: 'Owner', CEO: 'CEO', EXECUTIVE: 'Executive', DEPARTMENT: 'Department', SECTION: 'Section', TEAM: 'Team', POSITION: 'Position', OTHER: 'Other' };
const organizationTemplates = [
  { id: 'SMALL_BUSINESS', name: 'Small business', description: 'A lean owner-led company with practical operations, admin, sales, and marketing teams.', outline: 'Owner → CEO → Operations, Finance & Administration, Sales & Marketing' },
  { id: 'FUNCTIONAL', name: 'Functional company', description: 'A balanced corporate structure with executives, departments, sections, teams, and sample roles.', outline: 'Owner → CEO → Operations, Finance, Commercial, Technology' },
  { id: 'TECHNOLOGY', name: 'Technology company', description: 'Product, engineering, data, revenue, customer success, and corporate services.', outline: 'Owner → CEO → Product & Engineering, Revenue, Corporate Services' },
  { id: 'HEALTHCARE', name: 'Healthcare provider', description: 'Clinical services, patient access, health information, billing, and practice operations.', outline: 'Owner → CEO → Clinical Services, Patient Services, Corporate Services' },
  { id: 'RETAIL', name: 'Retail & multi-site', description: 'Store operations, supply chain, merchandising, commercial, and central support teams.', outline: 'Owner → CEO → Store Operations, Merchandising & Commercial, Corporate Services' },
  { id: 'PROFESSIONAL_SERVICES', name: 'Professional services', description: 'Client delivery, consulting, implementation, client success, growth, and shared services.', outline: 'Owner → CEO → Client Delivery, Growth, Corporate Services' },
  { id: 'NONPROFIT', name: 'Nonprofit', description: 'Program delivery, fundraising, partnerships, communications, and operational support.', outline: 'Owner → Executive Director → Programs, Development, Operations & Finance' },
];
const newDraft = (parentId: string | null, nodeType: string): Draft => ({ parentId, nodeType, name: '', title: '', employeeId: '', photoDataUrl: '', sortOrder: 0, active: true });
function getCardTextColors(hex: string) {
  const channels = [1, 3, 5].map(index => parseInt(hex.slice(index, index + 2), 16) / 255).map(value => value <= 0.04045 ? value / 12.92 : ((value + 0.055) / 1.055) ** 2.4);
  const luminance = 0.2126 * channels[0] + 0.7152 * channels[1] + 0.0722 * channels[2];
  return luminance < 0.36 ? { primary: '#ffffff', muted: '#e0eeeb', accent: '#b7f1e9' } : { primary: '#294542', muted: '#718783', accent: '#16877d' };
}

async function preparePhoto(file: File): Promise<string> {
  if (!['image/png', 'image/jpeg', 'image/webp'].includes(file.type)) throw new Error('Choose a PNG, JPG, or WebP photo.');
  const bitmap = await createImageBitmap(file);
  const scale = Math.min(1, 480 / Math.max(bitmap.width, bitmap.height));
  const canvas = document.createElement('canvas');
  canvas.width = Math.max(1, Math.round(bitmap.width * scale));
  canvas.height = Math.max(1, Math.round(bitmap.height * scale));
  const context = canvas.getContext('2d');
  if (!context) throw new Error('Could not prepare this photo.');
  context.drawImage(bitmap, 0, 0, canvas.width, canvas.height);
  bitmap.close();
  let quality = 0.82;
  let blob: Blob | null = null;
  while (quality >= 0.42) {
    blob = await new Promise<Blob | null>(resolve => canvas.toBlob(resolve, 'image/jpeg', quality));
    if (blob && blob.size <= 240 * 1024) break;
    quality -= 0.1;
  }
  if (!blob || blob.size > 250 * 1024) throw new Error('This photo is too large. Choose a smaller image.');
  return await new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => typeof reader.result === 'string' ? resolve(reader.result) : reject(new Error('Could not read the prepared photo.'));
    reader.onerror = () => reject(new Error('Could not read the prepared photo.'));
    reader.readAsDataURL(blob);
  });
}

export function OrganizationChart({ company, companies, onSelectCompany, request }: { company: OrgCompany | null; companies: OrgCompany[]; onSelectCompany: (id: string) => void; request: <T>(path: string, init?: RequestInit) => Promise<T> }) {
  const [nodes, setNodes] = useState<OrgNode[]>([]);
  const [employees, setEmployees] = useState<Employee[]>([]);
  const [view, setView] = useState<View>('chart');
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [loading, setLoading] = useState(false);
  const [busy, setBusy] = useState(false);
  const [ownerName, setOwnerName] = useState('');
  const [draft, setDraft] = useState<Draft | null>(null);
  const [seedDialogOpen, setSeedDialogOpen] = useState(false);
  const [seedTemplate, setSeedTemplate] = useState('FUNCTIONAL');
  const [seedOwnerName, setSeedOwnerName] = useState('');
  const [deleteNode, setDeleteNode] = useState<OrgNode | null>(null);
  const [photoView, setPhotoView] = useState<{ src: string; name: string } | null>(null);
  const [isChartFullscreen, setIsChartFullscreen] = useState(false);
  const [zoom, setZoom] = useState(100);
  const [compact, setCompact] = useState(false);
  const [cardBackground, setCardBackground] = useState('#ffffff');
  const [exporting, setExporting] = useState(false);
  const zoomContentRef = useRef<HTMLDivElement>(null);
  const [collapsed, setCollapsed] = useState<Set<string>>(new Set());
  const [draftError, setDraftError] = useState('');
  const base = company ? `/api/v1/companies/${company.id}/organization-chart` : '';

  async function load() {
    if (!company) { setNodes([]); setEmployees([]); return; }
    setLoading(true); setError('');
    try {
      const [chart, staff] = await Promise.all([
        request<OrgNode[]>(base),
        request<Employee[]>(`/api/v1/personnel/employees?companyId=${encodeURIComponent(company.id)}`),
      ]);
      setNodes(chart); setEmployees(staff);
    } catch (reason) { setError(reason instanceof Error ? reason.message : 'Could not load the organization chart.'); }
    finally { setLoading(false); }
  }
  useEffect(() => { setDraft(null); setSeedDialogOpen(false); setNotice(''); setOwnerName(company?.ownerName || ''); setSeedOwnerName(company?.ownerName || ''); void load(); }, [company?.id]);
  useEffect(() => {
    if (!company) return;
    const saved = localStorage.getItem(`org-chart-card-background:${company.id}`);
    setCardBackground(saved && /^#[0-9a-f]{6}$/i.test(saved) ? saved : '#ffffff');
  }, [company?.id]);
  useEffect(() => { if (!isChartFullscreen) return; const closeOnEscape = (event: KeyboardEvent) => { if (event.key === 'Escape') setIsChartFullscreen(false); }; document.addEventListener('keydown', closeOnEscape); return () => document.removeEventListener('keydown', closeOnEscape); }, [isChartFullscreen]);

  const root = nodes.find(node => node.parentId === null && node.nodeType === 'OWNER');
  const canSeedStructure = nodes.length === 0 || (nodes.length === 1 && nodes[0].nodeType === 'OWNER' && nodes[0].parentId === null);
  const byParent = useMemo(() => {
    const groups = new Map<string, OrgNode[]>();
    for (const node of nodes) {
      const key = node.parentId || 'ROOT';
      groups.set(key, [...(groups.get(key) || []), node]);
    }
    return groups;
  }, [nodes]);
  const childrenOf = (parentId: string) => byParent.get(parentId) || [];
  const ceoExists = Boolean(root && childrenOf(root.id).some(node => node.nodeType === 'CEO'));
  const employeeById = (id: string | null) => employees.find(employee => employee.id === id);
  function descendantIds(nodeId: string): Set<string> {
    const result = new Set<string>();
    const visit = (id: string) => childrenOf(id).forEach(child => { if (!result.has(child.id)) { result.add(child.id); visit(child.id); } });
    visit(nodeId);
    return result;
  }

  async function startChart(event: FormEvent) {
    event.preventDefault(); if (!company) return;
    setBusy(true); setError('');
    try {
      await request<OrgNode>(base, { method: 'POST', body: JSON.stringify({ nodeType: 'OWNER', name: ownerName.trim(), title: 'Owner', parentId: null, sortOrder: 0 }) });
      setNotice('Your organization chart is ready. Add a CEO if different from the owner, then build the reporting structure.'); await load();
    } catch (reason) { setError(reason instanceof Error ? reason.message : 'Could not start the organization chart.'); }
    finally { setBusy(false); }
  }
  function openCreate(parentId: string, nodeType = 'TEAM') { setDraft(newDraft(parentId, nodeType)); setDraftError(''); setError(''); }
  function openEdit(node: OrgNode) { setDraft({ id: node.id, parentId: node.parentId, nodeType: node.nodeType, name: node.name, title: node.title || '', employeeId: node.employeeId || '', photoDataUrl: node.photoDataUrl || '', sortOrder: node.sortOrder, active: node.active }); setDraftError(''); setError(''); }
  async function saveNode(event: FormEvent) {
    event.preventDefault(); if (!draft || !company) return;
    setBusy(true); setDraftError(''); setError('');
    try {
      const body = { nodeType: draft.nodeType, name: draft.name.trim(), title: draft.title.trim() || null, employeeId: draft.employeeId || null, photoDataUrl: draft.photoDataUrl || null, sortOrder: draft.sortOrder, active: draft.active };
      if (draft.id) await request(`${base}/${draft.id}`, { method: 'PUT', body: JSON.stringify({ ...body, parentId: draft.parentId }) });
      else await request(base, { method: 'POST', body: JSON.stringify({ ...body, parentId: draft.parentId }) });
      setDraft(null); setNotice(draft.id ? 'Chart node updated.' : `${typeNames[draft.nodeType] || 'Node'} added to the chart.`); await load();
    } catch (reason) { setDraftError(reason instanceof Error ? reason.message : 'Could not save this chart node.'); }
    finally { setBusy(false); }
  }
  async function removeNode() {
    if (!deleteNode) return;
    setBusy(true); setError('');
    try { await request(`${base}/${deleteNode.id}`, { method: 'DELETE' }); setNotice(`${typeNames[deleteNode.nodeType] || 'Node'} removed from the chart.`); setDeleteNode(null); await load(); }
    catch (reason) { setError(reason instanceof Error ? reason.message : 'Could not remove this chart node.'); }
    finally { setBusy(false); }
  }

  async function seedOrganization() {
    if (!company || !canSeedStructure) return;
    setBusy(true); setError('');
    try {
      await request<OrgNode[]>(`${base}/seed`, { method: 'POST', body: JSON.stringify({ template: seedTemplate, ownerName: seedOwnerName.trim() || null }) });
      setSeedDialogOpen(false); setCollapsed(new Set());
      setNotice(`${organizationTemplates.find(template => template.id === seedTemplate)?.name || 'Starter'} structure added. Assign your staff and customize the reporting lines.`);
      await load();
      window.requestAnimationFrame(() => showFullOrganization());
    } catch (reason) { setError(reason instanceof Error ? reason.message : 'Could not seed the organization structure.'); }
    finally { setBusy(false); }
  }

  async function seedEmployees() {
    setBusy(true); setError(''); setNotice('');
    try {
      const result = await request<{ companiesProcessed: number; employeesCreated: number; rolesSkipped: number }>('/api/v1/personnel/employees/seed', { method: 'POST' });
      await load();
      setNotice(result.employeesCreated
        ? `Added ${result.employeesCreated} sample employees across ${result.companiesProcessed} legal entities. ${result.rolesSkipped} roles were already assigned. Add their photos from People → employee profile.`
        : `No unassigned chart roles were found across ${result.companiesProcessed} legal entities. Seed a structure first, or assign existing staff to its roles.`);
    } catch (reason) { setError(reason instanceof Error ? reason.message : 'Could not seed employees.'); }
    finally { setBusy(false); }
  }

  function renderCard(node: OrgNode, depth = 0) {
    const children = childrenOf(node.id);
    const assigned = employeeById(node.employeeId);
    const photoDataUrl = node.photoDataUrl || node.employeePhotoDataUrl || assigned?.photoDataUrl || null;
    const isCollapsed = collapsed.has(node.id);
    const cardTextColors = getCardTextColors(cardBackground);
    return <div className={`org-chart-branch depth-${Math.min(depth, 5)}`} key={node.id}>
      <article className={`org-chart-card${compact ? ' is-compact' : ''} type-${node.nodeType.toLowerCase()}${node.active ? '' : ' is-inactive'}`} style={{ backgroundColor: cardBackground }}>
        <div className="org-chart-card-top">{photoDataUrl ? <button type="button" className="org-chart-avatar has-photo" onClick={() => setPhotoView({ src: photoDataUrl, name: assigned ? `${assigned.firstName} ${assigned.lastName}` : node.name })} aria-label={`View ${node.name} photo`}><img src={photoDataUrl} alt="" /></button> : <span className="org-chart-avatar">{node.nodeType === 'OWNER' ? '✦' : node.name.split(/\s+/).slice(0,2).map(part => part[0]).join('').toUpperCase()}</span>}<span className="org-chart-type" style={{ color: cardTextColors.muted }}>{typeNames[node.nodeType] || node.nodeType}</span>{children.length > 0 && <button type="button" className="org-chart-collapse" aria-label={`${isCollapsed ? 'Expand' : 'Collapse'} ${node.name}`} onClick={() => setCollapsed(current => { const next = new Set(current); isCollapsed ? next.delete(node.id) : next.add(node.id); return next; })}>{isCollapsed ? '+' : '−'}</button>}</div>
        <h3 style={{ color: cardTextColors.primary }}>{node.name}</h3><p className="org-chart-title" style={{ color: cardTextColors.muted }}>{node.title || typeNames[node.nodeType] || node.nodeType}</p>
        {assigned && <p className="org-chart-assigned" style={{ color: cardTextColors.accent }}>Assigned to {assigned.firstName} {assigned.lastName}</p>}
        {!node.active && <span className="org-chart-inactive-label">Inactive</span>}
        {compact ? <details className="org-chart-node-menu"><summary aria-label={`Actions for ${node.name}`} title="Node actions">⋯</summary><div className="org-chart-node-menu-items"><button type="button" onClick={event => { event.currentTarget.closest('details')?.removeAttribute('open'); openCreate(node.id, node.nodeType === 'OWNER' && !ceoExists ? 'CEO' : 'TEAM'); }}>＋ Add child</button><button type="button" onClick={event => { event.currentTarget.closest('details')?.removeAttribute('open'); openEdit(node); }}>Edit</button>{children.length === 0 && !node.employeeId && <button type="button" className="danger" onClick={event => { event.currentTarget.closest('details')?.removeAttribute('open'); setDeleteNode(node); }}>Remove</button>}</div></details> : <div className="org-chart-actions"><button type="button" onClick={() => openCreate(node.id, node.nodeType === 'OWNER' && !ceoExists ? 'CEO' : 'TEAM')}>＋ Add child</button><button type="button" onClick={() => openEdit(node)}>Edit</button>{children.length === 0 && !node.employeeId && <button type="button" className="danger" onClick={() => setDeleteNode(node)}>Remove</button>}</div>}
      </article>
      {children.length > 0 && !isCollapsed && <div className="org-chart-children">{children.map(child => renderCard(child, depth + 1))}</div>}
    </div>;
  }

  const flatRows: Array<{ node: OrgNode; depth: number }> = [];
  function flatten(node: OrgNode, depth = 0) { flatRows.push({ node, depth }); for (const child of childrenOf(node.id)) flatten(child, depth + 1); }
  if (root) flatten(root);
  const addTypes = draft?.parentId === root?.id && !ceoExists ? ['CEO','EXECUTIVE','DEPARTMENT','SECTION','TEAM','POSITION','OTHER'] : ['EXECUTIVE','DEPARTMENT','SECTION','TEAM','POSITION','OTHER'];
  const nodeTypeOptions = draft ? [...new Set([draft.nodeType, ...addTypes])] : addTypes;
  const blockedParents = draft?.id ? descendantIds(draft.id) : new Set<string>();
  const parentOptions = nodes.filter(node => node.active && node.id !== draft?.id && !blockedParents.has(node.id) && (draft?.nodeType !== 'CEO' || node.nodeType === 'OWNER'));

  async function exportChartAsImage() {
    if (!root) return;
    setExporting(true); setError('');
    try {
      type PositionedNode = { node: OrgNode; x: number; y: number; width: number; children: PositionedNode[] };
      const cardWidth = compact ? 250 : 268;
      const cardHeight = compact ? 96 : 194;
      const padding = 56;
      const siblingGap = compact ? 18 : 42;
      const levelGap = compact ? 170 : 282;
      const measuredWidths = new Map<string, number>();
      const nodeDepths = new Map<string, number>();
      const measure = (node: OrgNode, depth: number): number => {
        nodeDepths.set(node.id, depth);
        const children = childrenOf(node.id);
        const childrenWidth = children.reduce((sum, child) => sum + measure(child, depth + 1), 0) + Math.max(0, children.length - 1) * siblingGap;
        const width = Math.max(node.nodeType === 'OWNER' ? (compact ? 250 : 292) : cardWidth, childrenWidth);
        measuredWidths.set(node.id, width);
        return width;
      };
      const chartWidth = measure(root, 0);
      const maxDepth = Math.max(0, ...nodeDepths.values());
      const width = Math.ceil(chartWidth + padding * 2);
      const height = Math.ceil(padding * 2 + maxDepth * levelGap + cardHeight);
      const position = (node: OrgNode, left: number, depth: number): PositionedNode => {
        const children = childrenOf(node.id);
        const subtreeWidth = measuredWidths.get(node.id) || cardWidth;
        const ownWidth = node.nodeType === 'OWNER' ? (compact ? 250 : 292) : cardWidth;
        const childrenTotal = children.reduce((sum, child) => sum + (measuredWidths.get(child.id) || cardWidth), 0) + Math.max(0, children.length - 1) * siblingGap;
        let cursor = left + (subtreeWidth - childrenTotal) / 2;
        const positionedChildren = children.map(child => {
          const childPosition = position(child, cursor, depth + 1);
          cursor += (measuredWidths.get(child.id) || cardWidth) + siblingGap;
          return childPosition;
        });
        return { node, x: padding + left + (subtreeWidth - ownWidth) / 2, y: padding + depth * levelGap, width: ownWidth, children: positionedChildren };
      };
      const chartRoot = position(root, 0, 0);
      const pixelScale = Math.min(2, 8192 / width, 8192 / height, Math.sqrt(32000000 / (width * height)));
      const canvas = document.createElement('canvas');
      canvas.width = Math.max(1, Math.floor(width * pixelScale));
      canvas.height = Math.max(1, Math.floor(height * pixelScale));
      const context = canvas.getContext('2d');
      if (!context) throw new Error('Could not prepare the chart image.');
      context.scale(pixelScale, pixelScale);
      context.fillStyle = '#ffffff';
      context.fillRect(0, 0, width, height);

      const photos = new Map<string, ImageBitmap>();
      await Promise.all(nodes.filter(node => node.photoDataUrl || node.employeePhotoDataUrl || employeeById(node.employeeId)?.photoDataUrl).map(async node => {
        try {
          const [metadata, encoded] = (node.photoDataUrl || node.employeePhotoDataUrl || employeeById(node.employeeId)?.photoDataUrl)!.split(',', 2);
          const binary = atob(encoded);
          const bytes = new Uint8Array(binary.length);
          for (let index = 0; index < binary.length; index += 1) bytes[index] = binary.charCodeAt(index);
          const mediaType = metadata.slice(5).split(';')[0];
          photos.set(node.id, await createImageBitmap(new Blob([bytes], { type: mediaType })));
        } catch { throw new Error('Could not load a chart photo for export.'); }
      }));

      const accents: Record<string, string> = { OWNER: '#0b8b82', CEO: '#d9b85d', DEPARTMENT: '#168e83', SECTION: '#6e9fba', TEAM: '#6e9fba', EXECUTIVE: '#49aaa0', POSITION: '#758fc0', OTHER: '#839994' };
      const cardTextColors = getCardTextColors(cardBackground);
      const roundedRect = (x: number, y: number, rectWidth: number, rectHeight: number, radius: number) => {
        context.beginPath();
        context.roundRect(x, y, rectWidth, rectHeight, radius);
      };
      const wrappedLines = (text: string, maxWidth: number, maxLines: number) => {
        const lines: string[] = [];
        let line = '';
        text.split(/\s+/).forEach(word => {
          const candidate = line ? line + ' ' + word : word;
          if (line && context.measureText(candidate).width > maxWidth) { lines.push(line); line = word; }
          else line = candidate;
        });
        if (line) lines.push(line);
        if (lines.length > maxLines) {
          lines.length = maxLines;
          let last = lines[maxLines - 1];
          while (last.length && context.measureText(last + '…').width > maxWidth) last = last.slice(0, -1);
          lines[maxLines - 1] = last + '…';
        }
        return lines;
      };
      const draw = (item: PositionedNode) => {
        const centerX = item.x + item.width / 2;
        const junctionY = item.y + cardHeight + 42;
        if (item.children.length) {
          const childCenters = item.children.map(child => child.x + child.width / 2);
          context.strokeStyle = '#a9cec8';
          context.lineWidth = 1.5;
          context.beginPath();
          context.moveTo(centerX, item.y + cardHeight);
          context.lineTo(centerX, junctionY);
          if (childCenters.length > 1) {
            context.moveTo(Math.min(...childCenters), junctionY);
            context.lineTo(Math.max(...childCenters), junctionY);
          }
          childCenters.forEach(childCenter => { context.moveTo(childCenter, junctionY); context.lineTo(childCenter, item.y + levelGap); });
          context.stroke();
        }

        const node = item.node;
        const cardAccent = accents[node.nodeType] || accents.OTHER;
        context.save();
        context.shadowColor = 'rgba(31,74,68,.09)';
        context.shadowBlur = 18;
        context.shadowOffsetY = 7;
        roundedRect(item.x, item.y, item.width, cardHeight, 15);
        context.fillStyle = cardBackground;
        context.fill();
        context.restore();
        roundedRect(item.x, item.y, item.width, cardHeight, 15);
        context.strokeStyle = '#dce9e6';
        context.lineWidth = 1;
        context.stroke();
        context.save();
        roundedRect(item.x, item.y, item.width, cardHeight, 15);
        context.clip();
        context.fillStyle = cardAccent;
        context.fillRect(item.x, item.y, item.width, 4);
        context.restore();

        const avatarX = item.x + (compact ? 14 : 17);
        const avatarY = item.y + (compact ? 26 : 17);
        const avatarSize = compact ? 42 : 38;
        roundedRect(avatarX, avatarY, avatarSize, avatarSize, compact ? 24 : 11);
        context.fillStyle = '#e8f5f2';
        context.fill();
        const photo = photos.get(node.id);
        if (photo) {
          context.save();
          roundedRect(avatarX, avatarY, avatarSize, avatarSize, compact ? 24 : 11);
          context.clip();
          context.drawImage(photo, avatarX, avatarY, avatarSize, avatarSize);
          context.restore();
        } else {
          context.fillStyle = node.nodeType === 'OWNER' ? '#0b8b82' : '#118c82';
          context.font = '700 13px Manrope, Arial, sans-serif';
          context.textAlign = 'center';
          context.textBaseline = 'middle';
          const initials = node.nodeType === 'OWNER' ? '✦' : node.name.split(/\s+/).slice(0, 2).map(part => part[0]).join('').toUpperCase();
          context.fillText(initials, avatarX + avatarSize / 2, avatarY + avatarSize / 2);
        }
        context.textAlign = 'left';
        context.textBaseline = 'alphabetic';
        context.fillStyle = cardTextColors.muted;
        context.font = '800 11px Manrope, Arial, sans-serif';
        const textX = compact ? item.x + 74 : item.x + 17;
        context.fillText((typeNames[node.nodeType] || node.nodeType).toUpperCase(), compact ? textX : avatarX + avatarSize + 11, compact ? item.y + 20 : avatarY + 23, item.width - (compact ? 90 : 90));

        const textWidth = item.width - (compact ? 90 : 34);
        context.fillStyle = cardTextColors.primary;
        context.font = compact ? '700 16px Manrope, Arial, sans-serif' : '700 18px Manrope, Arial, sans-serif';
        const nameLines = wrappedLines(node.name, textWidth, compact ? 1 : 2);
        nameLines.forEach((text, index) => context.fillText(text, textX, item.y + (compact ? 42 : 82) + index * 22, textWidth));
        context.fillStyle = cardTextColors.muted;
        context.font = compact ? '400 12px Manrope, Arial, sans-serif' : '400 14px Manrope, Arial, sans-serif';
        const titleLines = wrappedLines(node.title || typeNames[node.nodeType] || node.nodeType, textWidth, compact ? 1 : 2);
        titleLines.forEach((text, index) => context.fillText(text, textX, item.y + (compact ? 61 : 130) + index * 18, textWidth));
        if (node.employeeId) {
          const employee = employeeById(node.employeeId);
          if (employee) {
            context.fillStyle = cardTextColors.accent;
            context.font = compact ? '500 10px Manrope, Arial, sans-serif' : '500 12px Manrope, Arial, sans-serif';
            context.fillText('Assigned to ' + employee.firstName + ' ' + employee.lastName, textX, item.y + (compact ? 79 : 181), textWidth);
          }
        }
        if (!node.active) {
          context.fillStyle = '#a26c29';
          context.font = '700 11px Manrope, Arial, sans-serif';
          context.fillText('Inactive', item.x + item.width - 55, item.y + cardHeight - 9);
        }
        item.children.forEach(draw);
      };
      draw(chartRoot);
      photos.forEach(photo => photo.close());

      const png = await new Promise<Blob | null>(resolve => canvas.toBlob(resolve, 'image/png'));
      if (!png) throw new Error('Could not create the PNG file.');
      const fileUrl = URL.createObjectURL(png);
      const link = document.createElement('a');
      link.href = fileUrl;
      link.download = (company?.companyCode || 'organization').replace(/[^a-z0-9_-]+/gi, '-') + '-chart.png';
      document.body.appendChild(link);
      link.click();
      link.remove();
      window.setTimeout(() => URL.revokeObjectURL(fileUrl), 1000);
    } catch (reason) { setError(reason instanceof Error ? reason.message : 'Could not export the organization chart.'); }
    finally { setExporting(false); }
  }
  function showFullOrganization() {
    setCollapsed(new Set());
    zoomContentRef.current?.closest('.org-chart-canvas')?.scrollIntoView({ block: 'start' });
    window.requestAnimationFrame(() => window.requestAnimationFrame(() => {
      const content = zoomContentRef.current;
      const canvas = content?.closest('.org-chart-canvas') as HTMLElement | null;
      if (!content || !canvas) return;
      const currentScale = zoom / 100 || 1;
      const chartWidth = content.scrollWidth / currentScale;
      const chartHeight = content.scrollHeight / currentScale;
      const availableWidth = Math.max(180, canvas.clientWidth - 44);
      const availableHeight = isChartFullscreen
        ? Math.max(180, canvas.clientHeight - 96)
        : Math.max(180, window.innerHeight - canvas.getBoundingClientRect().top - 32);
      const fit = Math.floor(Math.min(100, availableWidth / chartWidth * 100, availableHeight / chartHeight * 100));
      setZoom(Math.max(25, fit));
    }));
  }
  return <div className="org-chart-page">
    <header className="org-chart-heading"><div><p className="eyebrow">ORGANIZATION DESIGN</p><h2>Organization chart</h2><p>Build the structure people work in, from ownership through every team and role.</p></div><div className="org-chart-heading-actions">{companies.length > 0 && <label className="people-company-select"><span>Legal entity</span><select value={company?.id || ''} onChange={event => onSelectCompany(event.target.value)}>{companies.map(item => <option key={item.id} value={item.id}>{item.companyCode} · {item.legalName}</option>)}</select></label>}{company && <><button type="button" className="session-revoke" onClick={() => void seedEmployees()} disabled={busy} title="Seed demo employees into every unfilled role across your legal entities">♙ {busy ? 'Seeding…' : 'Seed employees'}</button><button type="button" className="org-chart-seed-trigger" onClick={() => setSeedDialogOpen(true)} disabled={busy}>✦ Seed structure</button></>}<div className="org-view-switch" role="tablist" aria-label="Organization chart view"><button type="button" role="tab" aria-selected={view === 'chart'} className={view === 'chart' ? 'active' : ''} onClick={() => setView('chart')}>Chart</button><button type="button" role="tab" aria-selected={view === 'table'} className={view === 'table' ? 'active' : ''} onClick={() => setView('table')}>Table</button></div></div></header>
    {!company ? <section className="org-chart-empty"><span>▦</span><h3>Add a legal entity to get started</h3><p>Create a company in Entity details, then build its organization chart here.</p></section> : <>
      <div className="org-chart-context"><span className="org-chart-context-icon">▦</span><div><small>CHART FOR</small><strong>{company.legalName}</strong></div><span className="org-chart-context-code">{company.companyCode}</span></div>
      {error && <div className="platform-alert" role="alert">{error}</div>}{notice && <p className="platform-notice" role="status">{notice}</p>}
      {seedDialogOpen && <div className="org-dialog-backdrop org-seed-backdrop" role="presentation" onMouseDown={event => { if (event.target === event.currentTarget && !busy) setSeedDialogOpen(false); }}><section className="org-seed-dialog" role="dialog" aria-modal="true" aria-labelledby="org-seed-title"><header><div><p className="eyebrow">START WITH A REALISTIC TEMPLATE</p><h3 id="org-seed-title">Seed an organization structure</h3><p>Choose a starter hierarchy. You can rename units, move them, add levels, and assign staff after seeding.</p></div><button type="button" className="org-chart-close" aria-label="Close templates" onClick={() => setSeedDialogOpen(false)} disabled={busy}>×</button></header>{!canSeedStructure && <div className="platform-alert" role="alert">Templates can seed an empty chart or an owner-only chart. This chart already has other nodes; remove its unused structure first to avoid duplicates.</div>}{!root && <label className="people-field org-seed-owner"><span>Owner name</span><input value={seedOwnerName} onChange={event => setSeedOwnerName(event.target.value)} maxLength={200} placeholder="Owner’s name" /></label>}<div className="org-seed-template-grid">{organizationTemplates.map(template => <button type="button" key={template.id} className={`org-seed-template${seedTemplate === template.id ? ' selected' : ''}`} aria-pressed={seedTemplate === template.id} onClick={() => setSeedTemplate(template.id)}><span className="org-seed-template-icon">▦</span><strong>{template.name}</strong><small>{template.description}</small><span className="org-seed-outline">{template.outline}</span></button>)}</div><footer><button type="button" className="session-revoke" onClick={() => setSeedDialogOpen(false)} disabled={busy}>Cancel</button><button type="button" className="security-submit" onClick={() => void seedOrganization()} disabled={busy || !canSeedStructure}>{busy ? 'Creating structure…' : `Seed ${organizationTemplates.find(template => template.id === seedTemplate)?.name || 'template'}`}</button></footer></section></div>}
      {draft && <div className="org-dialog-backdrop org-editor-backdrop" role="presentation" onMouseDown={event => { if (event.target === event.currentTarget) setDraft(null); }}><section className="org-node-modal" role="dialog" aria-modal="true" aria-labelledby="org-node-modal-title"><form className="org-node-editor" onSubmit={event => void saveNode(event)}><div className="org-node-editor-heading"><div><p className="eyebrow">{draft.id ? 'EDIT CHART NODE' : 'ADD TO ORGANIZATION'}</p><h3 id="org-node-modal-title">{draft.id ? 'Update node' : `Add ${typeNames[draft.nodeType]?.toLowerCase() || 'child'}`}</h3></div><button type="button" className="org-chart-close" aria-label="Close editor" onClick={() => setDraft(null)}>×</button></div><div className="org-node-editor-grid"><label className="people-field"><span>Node type</span><select value={draft.nodeType} disabled={draft.nodeType === 'OWNER'} onChange={event => { const nodeType = event.target.value; setDraft({ ...draft, nodeType, ...(nodeType === 'CEO' && root ? { parentId: root.id } : {}) }); }}>{nodeTypeOptions.map(type => <option key={type} value={type}>{typeNames[type]}</option>)}</select></label><label className="people-field"><span>{draft.nodeType === 'OWNER' ? 'Owner name' : 'Label / unit name'}</span><input value={draft.name} onChange={event => setDraft({ ...draft, name: event.target.value })} required maxLength={200} placeholder={draft.nodeType === 'OWNER' ? 'Owner’s full name' : 'e.g. Finance'} /></label>{draft.id && draft.nodeType !== 'OWNER' && <label className="people-field"><span>Reports to</span><select value={draft.parentId || ''} onChange={event => setDraft({ ...draft, parentId: event.target.value || null })}>{parentOptions.map(node => <option key={node.id} value={node.id}>{node.name} · {typeNames[node.nodeType] || node.nodeType}</option>)}</select></label>}<label className="people-field"><span>Position or description</span><input value={draft.title} onChange={event => setDraft({ ...draft, title: event.target.value })} maxLength={160} placeholder="e.g. Finance Director" /></label><label className="people-field"><span>Assign staff member (optional)</span><select value={draft.employeeId} onChange={event => setDraft({ ...draft, employeeId: event.target.value })}><option value="">Unassigned</option>{employees.map(person => <option key={person.id} value={person.id}>{person.firstName} {person.lastName}</option>)}</select></label><label className="people-field org-photo-field"><span>Photo (optional)</span><input type="file" accept="image/png,image/jpeg,image/webp" onChange={event => { const file = event.currentTarget.files?.[0]; event.currentTarget.value = ''; if (file) void preparePhoto(file).then(photoDataUrl => setDraft(current => current ? { ...current, photoDataUrl } : current)).catch(reason => setDraftError(reason instanceof Error ? reason.message : 'Could not prepare this photo.')); }} /><small>PNG, JPG, or WebP. Images are resized before saving.</small></label>{draft.photoDataUrl && <div className="org-photo-preview"><img src={draft.photoDataUrl} alt="Chart node preview" /><button type="button" className="session-revoke" onClick={() => setDraft({ ...draft, photoDataUrl: '' })}>Remove photo</button></div>}</div>{draft.id && <label className="org-node-active"><input type="checkbox" checked={draft.active} onChange={event => setDraft({ ...draft, active: event.target.checked })} /> Active</label>}{draftError && <div className="platform-alert" role="alert">{draftError}</div>}<div className="org-node-editor-footer"><button type="button" className="session-revoke" onClick={() => setDraft(null)}>Cancel</button><button type="submit" className="security-submit" disabled={busy}>{busy ? 'Saving…' : draft.id ? 'Save changes' : 'Add to chart'}</button></div></form></section></div>}
      {loading ? <div className="org-chart-empty"><span className="org-chart-spinner">◌</span><p>Loading the organization chart…</p></div> : !root ? <form className="org-chart-first-node" onSubmit={event => void startChart(event)}><span className="org-chart-first-icon">✦</span><p className="eyebrow">START AT THE TOP</p><h3>Add the organization owner</h3><p>This is the root of the chart. If the owner also serves as CEO, use a combined title and skip the separate CEO node.</p><label className="people-field"><span>Owner name</span><input value={ownerName} onChange={event => setOwnerName(event.target.value)} required maxLength={200} /></label><button type="submit" className="security-submit" disabled={busy}>{busy ? 'Creating…' : 'Create owner node'}</button></form> : view === 'chart' ? <section className={`org-chart-canvas${isChartFullscreen ? ' is-fullscreen' : ''}`} aria-label="Organization chart"><div className="org-chart-tools"><div className="org-chart-zoom-controls" aria-label="Chart zoom controls"><button type="button" onClick={() => setZoom(value => Math.max(25, value - 10))} disabled={zoom <= 25} aria-label="Zoom out">−</button><span>{zoom}%</span><button type="button" onClick={() => setZoom(value => Math.min(160, value + 10))} disabled={zoom >= 160} aria-label="Zoom in">＋</button><button type="button" onClick={() => setZoom(100)} disabled={zoom === 100}>Reset</button><button type="button" className="org-chart-compact-toggle" role="switch" aria-checked={compact} onClick={() => setCompact(value => !value)}><span className="org-chart-switch-track" aria-hidden="true"><span /></span><span>Compact</span></button><label className="org-chart-background-picker" title="Choose the entity card background"><input type="color" aria-label="Entity card background color" value={cardBackground} onChange={event => { const value = event.target.value; setCardBackground(value); if (company) localStorage.setItem(`org-chart-card-background:${company.id}`, value); }} /><span>Card color</span></label></div><div className="org-chart-output-controls"><button type="button" onClick={showFullOrganization} title="Expand every level and fit the chart">Show full org</button><button type="button" onClick={() => window.print()}>Print</button><button type="button" className="org-chart-export" onClick={() => void exportChartAsImage()} disabled={exporting}>{exporting ? 'Exporting…' : 'Export PNG'}</button></div></div><button type="button" className="org-chart-expand" onClick={() => setIsChartFullscreen(value => !value)}>{isChartFullscreen ? '↙ Exit full screen' : '⛶ Full screen'}</button><div ref={zoomContentRef} className="org-chart-zoom-content" style={{ zoom: zoom / 100 }}><div className="org-chart-root-row">{renderCard(root)}</div>{!ceoExists && <button type="button" className="org-chart-add-ceo" onClick={() => openCreate(root.id,'CEO')}>＋ Add CEO below owner</button>}</div></section> : <section className="org-chart-table-wrap"><table className="org-chart-table"><thead><tr><th>ORGANIZATION UNIT</th><th>TYPE</th><th>POSITION / DESCRIPTION</th><th>ASSIGNED STAFF</th><th>STATUS</th><th>ACTIONS</th></tr></thead><tbody>{flatRows.map(({node,depth})=><tr key={node.id}><td><span style={{ paddingLeft: `${depth * 24}px` }} className="org-table-name">{depth > 0 && <span aria-hidden="true">└</span>}<strong>{node.name}</strong></span></td><td>{typeNames[node.nodeType] || node.nodeType}</td><td>{node.title || '—'}</td><td>{employeeById(node.employeeId) ? `${employeeById(node.employeeId)?.firstName} ${employeeById(node.employeeId)?.lastName}` : 'Unassigned'}</td><td><span className={`org-table-status${node.active ? ' active' : ''}`}>{node.active ? 'Active' : 'Inactive'}</span></td><td><div className="org-chart-actions"><button type="button" onClick={() => openCreate(node.id,'TEAM')}>Add child</button><button type="button" onClick={() => openEdit(node)}>Edit</button>{childrenOf(node.id).length === 0 && !node.employeeId && <button type="button" className="danger" onClick={() => setDeleteNode(node)}>Remove</button>}</div></td></tr>)}</tbody></table></section>}
    </>}
    {photoView && <div className="org-dialog-backdrop org-photo-backdrop" role="presentation" onMouseDown={event => { if (event.target === event.currentTarget) setPhotoView(null); }}><section className="org-photo-dialog" role="dialog" aria-modal="true" aria-label={`${photoView.name} photo`}><button type="button" className="org-chart-close" aria-label="Close photo" onClick={() => setPhotoView(null)}>×</button><img src={photoView.src} alt={`${photoView.name}`} /><strong>{photoView.name}</strong></section></div>}
    {deleteNode && <div className="org-dialog-backdrop" role="presentation" onMouseDown={event => { if (event.target === event.currentTarget) setDeleteNode(null); }}><section className="org-delete-dialog" role="alertdialog" aria-modal="true" aria-labelledby="org-delete-title"><p className="eyebrow">REMOVE CHART NODE</p><h3 id="org-delete-title">Remove {deleteNode.name}?</h3><p>Only an unassigned node with no child nodes can be removed. Unassign staff or move its children first to preserve personnel history.</p><footer><button type="button" className="session-revoke" onClick={() => setDeleteNode(null)} disabled={busy}>Cancel</button><button type="button" className="org-delete-confirm" onClick={() => void removeNode()} disabled={busy}>{busy ? 'Removing…' : 'Remove node'}</button></footer></section></div>}
  </div>;
}
