import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location('performance_report', Path(__file__).parents[1] / 'performance_report.py')
report = importlib.util.module_from_spec(spec)
spec.loader.exec_module(report)


class PerformanceReportTest(unittest.TestCase):
    def test_percentiles_and_metric_groups(self):
        log = '\n'.join(f'Performance: playback_to_first_frame_ms={n}' for n in range(1, 21))
        log += '\nPerformance: startup_to_composition_ms=50'
        data = report.summarize(log)
        self.assertEqual({'count': 20, 'min_ms': 1, 'max_ms': 20, 'p50_ms': 10, 'p95_ms': 19},
                         data['playback_to_first_frame_ms'])
        self.assertEqual(1, data['startup_to_composition_ms']['count'])

    def test_empty_or_unrelated_logs_never_invent_samples(self):
        self.assertEqual({}, report.summarize('No first frame was observed'))


if __name__ == '__main__':
    unittest.main()
