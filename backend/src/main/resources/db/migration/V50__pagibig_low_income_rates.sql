-- Effective-dated Pag-IBIG low-income employee rate. Preserve existing statutory history.
ALTER TABLE statutory_contribution_rule
    ADD COLUMN low_income_threshold numeric(18,4),
    ADD COLUMN low_income_employee_rate numeric(18,4);

UPDATE statutory_contribution_rule
SET low_income_threshold = 1500, low_income_employee_rate = 0.01
WHERE agency = 'PAGIBIG' AND effective_from = DATE '2024-02-01';
