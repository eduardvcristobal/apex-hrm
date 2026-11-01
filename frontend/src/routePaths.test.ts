import { describe, expect, it } from 'vitest';
import { routePath, routeSlug } from './routePaths';

describe('workspace navigation slugs', () => {
  it('creates predictable, URL-safe slugs', () => {
    expect(routeSlug('Time & attendance')).toBe('time-and-attendance');
    expect(routeSlug('Learning & development')).toBe('learning-and-development');
    expect(routeSlug('  Tax & compliance  ')).toBe('tax-and-compliance');
  });
  it('creates canonical workspace route paths', () => {
    expect(routePath('/workspace', 'Payroll')).toBe('/workspace/payroll');
    expect(routePath('/workspace', 'Users & staff')).toBe('/workspace/users-and-staff');
  });
});
