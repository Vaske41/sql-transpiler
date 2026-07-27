from wave3_measure import BASELINE, TARGET, COHORT, classify

def test_pins_are_the_measured_baseline():
    assert COHORT == 1426
    assert BASELINE == {"SUCCESS": 966, "PARSE": 309, "REFUSED": 151}
    assert TARGET == 1213  # ceil(0.85 * 1426)

def test_classify_maps_exit_codes():
    assert classify(0, "") == "SUCCESS"
    assert classify(2, "unsupported: Unsupported feature at 1:5: type SIGNED") == "REFUSED"
    assert classify(1, "line 1:5 mismatched input 'FOO'") == "PARSE"
