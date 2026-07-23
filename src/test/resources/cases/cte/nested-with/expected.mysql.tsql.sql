WITH inner_cte AS (SELECT 1 AS x), outer_cte AS (SELECT inner_cte.x FROM inner_cte) SELECT outer_cte.x FROM outer_cte;
