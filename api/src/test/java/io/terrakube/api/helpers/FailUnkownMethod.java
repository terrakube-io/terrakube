package io.terrakube.api.helpers;

import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

public class FailUnkownMethod<T> implements Answer<T> {
    @Override
    public T answer(InvocationOnMock invocation) throws Throwable {
        throw new UnsupportedOperationException("Unimplemented method " + invocation.getMethod());
    }
}