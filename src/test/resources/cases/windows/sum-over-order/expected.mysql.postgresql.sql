SELECT SUM(amount) OVER(ORDER BY ts NULLS FIRST) AS running FROM payments;
