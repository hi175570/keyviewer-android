package com.KV;

import android.os.ParcelFileDescriptor;

interface IKeyInjectorService {
    ParcelFileDescriptor connect() = 1;
    void destroy() = 16777114;
}
