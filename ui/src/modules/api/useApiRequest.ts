import { useCallback, useState } from "react";
import { ApiResponse, ErrorInformation } from "./types";
import { notification } from "antd";

type UseApiRequestOptions<TResponse, TRequest = unknown> = {
  action: (data?: TRequest) => Promise<ApiResponse<TResponse>>;
  onReturn: (data: TResponse) => void;
  showErrorAsNotification?: boolean;
  returnsData?: boolean;
  requestErrorInfo?: ErrorInformation;
  errorInfo?: ErrorInformation;
};

type UseApiRequestReturn<TRequest = unknown> = {
  error: ErrorInformation | undefined;
  loading: boolean;
  execute: (data?: TRequest) => Promise<void>;
  notificationContext: React.ReactElement;
};

function useApiRequest<TResponse, TRequest>({
  action,
  onReturn,

  errorInfo,
  requestErrorInfo,
  showErrorAsNotification = false,
  returnsData = true,
}: UseApiRequestOptions<TResponse, TRequest>): UseApiRequestReturn<TRequest> {
  const [loading, setLoading] = useState<boolean>(false);
  const [error, setError] = useState<ErrorInformation>();
  const [api, contextHolder] = notification.useNotification();

  const processError = useCallback(
    (err: ErrorInformation) => {
      if (showErrorAsNotification) {
        api.error({
          message: err.title,
          description: err.message,
        });
        return;
      }
      setError(err);
    },
    [showErrorAsNotification]
  );

  const execute = useCallback(
    async (data?: TRequest) => {
      try {
        setLoading(true);
        setError(undefined);
        const response = await action(data);

        if (!response.isError) {
          if (returnsData && response.data === undefined) {
            const err: ErrorInformation = {
              title: errorInfo?.title ?? "Operation failed",
              message: errorInfo?.message ?? "Failed due to an unknown error",
            };
            processError(err);
            return;
          }

          onReturn(response.data!);
          return;
        }

        const err: ErrorInformation = {
          title: requestErrorInfo?.title || response.error?.status || "Operation failed",
          message: requestErrorInfo?.message || response.error?.message || "Failed due to an unknown error",
        };
        processError(err);
      } catch (error: unknown) {
        console.error(error);
        const err: ErrorInformation = {
          title: errorInfo?.title ?? "Operation failed",
          message: errorInfo?.message ?? "Failed due to an unknown error",
        };
        processError(err);
      } finally {
        setLoading(false);
      }
    },
    [requestErrorInfo, processError, onReturn, action]
  );

  return {
    error,
    loading,
    execute,
    notificationContext: contextHolder,
  };
}

export default useApiRequest;
