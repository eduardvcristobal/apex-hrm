-- BIR graduated annual compensation tax schedule, effective 2023-01-01 onward.
-- Values from RA 10963 / BIR annual schedule. Apply later changes using new effective-dated rows.
INSERT INTO withholding_tax_bracket (effective_from, period_type, lower_bound, upper_bound, base_tax, rate) VALUES
 (DATE '2023-01-01', 'ANNUAL',       0,  250000,       0, 0.00),
 (DATE '2023-01-01', 'ANNUAL',  250000,  400000,       0, 0.15),
 (DATE '2023-01-01', 'ANNUAL',  400000,  800000,   22500, 0.20),
 (DATE '2023-01-01', 'ANNUAL',  800000, 2000000,  102500, 0.25),
 (DATE '2023-01-01', 'ANNUAL', 2000000, 8000000,  402500, 0.30),
 (DATE '2023-01-01', 'ANNUAL', 8000000,    NULL, 2202500, 0.35);
