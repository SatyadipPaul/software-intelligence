import sys
from pathlib import Path

# Lets `pytest experiments/jev-graphrag/tests` work from any folder, not only from the experiment's own.
sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
