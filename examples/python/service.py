"""Service times for examples/python/mm1-python.cpnd.

In a real study this function would be fitted to measured data (e.g. with pandas or SciPy);
here it draws from an exponential distribution with mean 50, so the results can be compared with
queueing theory (time in system W = 100).
"""
import random


def service_time():
    return round(random.expovariate(1 / 50))
