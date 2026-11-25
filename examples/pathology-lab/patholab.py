"""delay_section for the pathology lab example (patholab.cpnd).

The model calls this function every time a worker sections a case. Here it is a simple
placeholder; in a real lab it would be fitted to measured sectioning times, e.g. per worker.

Use `random` (or NumPy) for randomness: the simulation seeds both, so a run repeats for a seed.
"""
import math
import random


def delay_section(worker, skill):
    """Minutes `worker` takes to section a case; a higher `skill` is faster."""
    mean = 300 / skill  # skill 5: 60 minutes, skill 10: 30 minutes
    sigma = 0.4
    return max(1, round(random.lognormvariate(math.log(mean) - sigma * sigma / 2, sigma)))
