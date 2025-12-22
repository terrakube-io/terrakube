import axios from "axios";
import getUserFromStorage from "./authUser";

const axiosInstance = axios.create({
  baseURL: window._env_.REACT_APP_TERRAKUBE_API_URL,
});

export const axiosClient = axios.create({
  baseURL: window._env_.REACT_APP_TERRAKUBE_API_URL,
});

export const axiosGraphQL = axios.create({
  baseURL: new URL(window._env_.REACT_APP_TERRAKUBE_API_URL).origin + "/graphql/api/v1",
});

axiosInstance.interceptors.request.use(
  function (config) {
    const user = getUserFromStorage();
    const accessToken = user?.access_token;
    config.headers["Authorization"] = `Bearer ${accessToken}`;
    return config;
  },
  function (error) {
    Promise.reject(error);
  }
);

axiosGraphQL.interceptors.request.use(
  function (config) {
    const user = getUserFromStorage();
    const accessToken = user?.access_token;
    config.headers["Authorization"] = `Bearer ${accessToken}`;
    return config;
  },
  function (error) {
    Promise.reject(error);
  }
);

export default axiosInstance;
