/** Shared navigation route normalization used by the application and regression tests. */
export const routeSlug = (label: string): string =>
  label.toLowerCase().replaceAll('&', 'and').replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '');

export const routePath = (base: string, label: string): string => `${base}/${routeSlug(label)}`;
