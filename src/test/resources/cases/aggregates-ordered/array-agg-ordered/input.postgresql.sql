SELECT ARRAY_AGG(lt.milliseconds ORDER BY lt.lap) FROM lap_times lt;
