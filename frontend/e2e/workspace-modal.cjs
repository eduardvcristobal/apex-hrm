const { expect } = require('@playwright/test');

async function closeWorkspaceDialog(page) {
  const open = page.locator('dialog.workspace-editor-dialog[open]').last();
  if (await open.count()) {
    await open.getByRole('button', { name: /^Close / }).click();
    await expect(open).not.toBeVisible();
  }
}

async function openFormEditor(page, form, knownTitle, triggerLabel) {
  if (await form.isVisible().catch(() => false)) return form;
  let title = knownTitle;
  if (!title) {
    const dialog = page.locator('dialog.workspace-editor-dialog').filter({ has: form }).first();
    title = await dialog.getAttribute('aria-label', { timeout: 2_000 }).catch(() => null);
  }
  if (!title) throw new Error('Could not determine the hidden editor dialog; pass its title to openFormEditor.');
  await closeWorkspaceDialog(page);
  await page.getByRole('button', { name: `＋ ${triggerLabel || title}`, exact: true }).click();
  await expect(form).toBeVisible();
  return form;
}

async function openEditor(page, title) {
  await closeWorkspaceDialog(page);
  await page.getByRole('button', { name: `＋ ${title}`, exact: true }).click();
  const dialog = page.getByRole('dialog', { name: title });
  await expect(dialog).toBeVisible();
  return dialog;
}

module.exports = { openFormEditor, openEditor, closeWorkspaceDialog };
