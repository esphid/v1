Run from the repository root (CMake and a host C compiler required):

```sh
cmake -S firmware/test/host -B firmware/build/host && cmake --build firmware/build/host && ctest --test-dir firmware/build/host --output-on-failure
```

Uses the managed cJSON copy after IDF reconfigure, or downloads upstream cJSON
1.7.19 otherwise. AddressSanitizer and UndefinedBehaviorSanitizer are enabled.
