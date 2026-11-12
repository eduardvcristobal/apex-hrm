-- BIR RR 11-2018 Annex E, effective January 1, 2023 and onwards.
-- Store the BIR daily and weekly withholding bands in the same effective-dated table used by payroll.
INSERT INTO withholding_tax_bracket
  (effective_from,period_type,lower_bound,upper_bound,base_tax,rate,source_reference)
SELECT DATE '2023-01-01', bands.period_type, bands.lower_bound, bands.upper_bound, bands.base_tax, bands.rate,
       'BIR RR 11-2018 Annex E (effective 2023-01-01)'
FROM (VALUES
  ('DAILY', 0::numeric, 685::numeric, 0::numeric, 0::numeric),
  ('DAILY', 685::numeric, 1096::numeric, 0::numeric, 0.15::numeric),
  ('DAILY', 1096::numeric, 2192::numeric, 61.65::numeric, 0.20::numeric),
  ('DAILY', 2192::numeric, 5479::numeric, 280.85::numeric, 0.25::numeric),
  ('DAILY', 5479::numeric, 21918::numeric, 1102.60::numeric, 0.30::numeric),
  ('DAILY', 21918::numeric, NULL::numeric, 6034.00::numeric, 0.35::numeric),
  ('WEEKLY', 0::numeric, 4808::numeric, 0::numeric, 0::numeric),
  ('WEEKLY', 4808::numeric, 7692::numeric, 0::numeric, 0.15::numeric),
  ('WEEKLY', 7692::numeric, 15385::numeric, 432.60::numeric, 0.20::numeric),
  ('WEEKLY', 15385::numeric, 38462::numeric, 1971.20::numeric, 0.25::numeric),
  ('WEEKLY', 38462::numeric, 153846::numeric, 7740.45::numeric, 0.30::numeric),
  ('WEEKLY', 153846::numeric, NULL::numeric, 42355.65::numeric, 0.35::numeric)
) AS bands(period_type,lower_bound,upper_bound,base_tax,rate)
WHERE NOT EXISTS (
  SELECT 1 FROM withholding_tax_bracket existing
  WHERE existing.period_type=bands.period_type
    AND existing.effective_from=DATE '2023-01-01'
    AND existing.lower_bound=bands.lower_bound
);
