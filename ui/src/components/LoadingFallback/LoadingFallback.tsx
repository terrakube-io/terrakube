import { Spin } from "antd";
import "./LoadingFallback.css";

export const LoadingFallback = () => {
  return (
    <div className="loading-fallback-container">
      <Spin size="large" />
    </div>
  );
};

export default LoadingFallback;
