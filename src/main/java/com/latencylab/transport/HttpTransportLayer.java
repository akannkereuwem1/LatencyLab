package com.latencylab.transport;

import com.latencylab.model.RequestStep;

public interface HttpTransportLayer {
    HttpResponseResult execute(RequestStep step);
}
