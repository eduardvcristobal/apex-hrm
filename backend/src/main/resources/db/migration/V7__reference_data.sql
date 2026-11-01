-- Global Philippine statutory reference data. These rows are rules, not code: add a new row with a later
-- effective_from when an agency changes a rate, and never edit history. VERIFY every value against the
-- current SSS / PhilHealth / Pag-IBIG circulars and the BIR withholding table before going live.

-- SSS: 15% of MSC (5% employee / 10% employer), MSC 5,000-35,000 in 500 steps, EC 10 below 15,000 else 30,
-- MSC above 20,000 funds the MPF/WISP portion.
INSERT INTO statutory_contribution_rule
  (agency, effective_from, employee_rate, employer_rate, min_base, max_base, msc_step, ec_low, ec_high, ec_threshold, mpf_threshold)
VALUES ('SSS', DATE '2025-01-01', 0.05, 0.10, 5000, 35000, 500, 10, 30, 15000, 20000);

-- PhilHealth: 5% of basic monthly salary shared equally, floor 10,000, ceiling 100,000.
INSERT INTO statutory_contribution_rule
  (agency, effective_from, employee_rate, employer_rate, min_base, max_base)
VALUES ('PHILHEALTH', DATE '2024-01-01', 0.025, 0.025, 10000, 100000);

-- Pag-IBIG: 2% employee / 2% employer on monthly compensation capped at a 10,000 fund salary.
-- (Members earning 1,500 or less pay a 1% employee rate; not modelled yet.)
INSERT INTO statutory_contribution_rule
  (agency, effective_from, employee_rate, employer_rate, min_base, max_base)
VALUES ('PAGIBIG', DATE '2024-02-01', 0.02, 0.02, 0, 10000);

-- BIR withholding tax on compensation (TRAIN, effective 2023-01-01). tax = base_tax + rate x (taxable - lower_bound).
INSERT INTO withholding_tax_bracket (effective_from, period_type, lower_bound, upper_bound, base_tax, rate) VALUES
 (DATE '2023-01-01', 'MONTHLY',       0,      20833,      0,         0.00),
 (DATE '2023-01-01', 'MONTHLY',   20833,      33333,      0,         0.15),
 (DATE '2023-01-01', 'MONTHLY',   33333,      66667,   1875.00,      0.20),
 (DATE '2023-01-01', 'MONTHLY',   66667,     166667,   8541.80,      0.25),
 (DATE '2023-01-01', 'MONTHLY',  166667,     666667,  33541.80,      0.30),
 (DATE '2023-01-01', 'MONTHLY',  666667,       NULL, 183541.80,      0.35),
 (DATE '2023-01-01', 'SEMI_MONTHLY',      0,  10417,      0,         0.00),
 (DATE '2023-01-01', 'SEMI_MONTHLY',  10417,  16667,      0,         0.15),
 (DATE '2023-01-01', 'SEMI_MONTHLY',  16667,  33333,    937.50,      0.20),
 (DATE '2023-01-01', 'SEMI_MONTHLY',  33333,  83333,   4270.70,      0.25),
 (DATE '2023-01-01', 'SEMI_MONTHLY',  83333, 333333,  16770.70,      0.30),
 (DATE '2023-01-01', 'SEMI_MONTHLY', 333333,   NULL,  91770.70,      0.35);

-- Premium pay matrix (DOLE). OT hourly = hourly x worked_multiplier x ot_multiplier; night differential is
-- an extra 10% of whichever hourly rate applies. base_included_monthly: a monthly salary already pays that day.
INSERT INTO premium_rule (day_type, worked_multiplier, ot_multiplier, unworked_multiplier, base_included_monthly, night_diff_rate, effective_from) VALUES
 ('ORDINARY',                 1.00, 1.25, 0.00, true,  0.10, DATE '1990-01-01'),
 ('REST_DAY',                 1.30, 1.30, 0.00, false, 0.10, DATE '1990-01-01'),
 ('SPECIAL_HOLIDAY',          1.30, 1.30, 0.00, true,  0.10, DATE '1990-01-01'),
 ('SPECIAL_HOLIDAY_REST_DAY', 1.50, 1.30, 0.00, false, 0.10, DATE '1990-01-01'),
 ('REGULAR_HOLIDAY',          2.00, 1.30, 1.00, true,  0.10, DATE '1990-01-01'),
 ('REGULAR_HOLIDAY_REST_DAY', 2.60, 1.30, 1.00, false, 0.10, DATE '1990-01-01');

-- Minimum wage floor (per workplace region). NCR non-agriculture: 755/day from 2026-09-26 (Wage Order NCR-28).
-- Wage orders change often and vary by region/sector; load the rest through /api/reference/wage-orders.
INSERT INTO wage_order (region_code, sector, daily_rate, effective_from, reference) VALUES
 ('NCR', 'NON_AGRI', 695, DATE '2025-07-18', 'NCR-26'),
 ('NCR', 'NON_AGRI', 755, DATE '2026-09-26', 'NCR-28');
