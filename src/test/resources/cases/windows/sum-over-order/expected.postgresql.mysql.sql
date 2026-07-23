SELECT SUM(amount) OVER(ORDER BY ts) AS running FROM payments;
