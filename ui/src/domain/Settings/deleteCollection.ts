import { message } from "antd";
import axiosInstance from "../../config/axiosConfig";

// Deletes a variable collection: its variables and workspace references first, then the collection itself.
export const deleteCollection = async (orgid: string | undefined, id: string) => {
  const base = `organization/${orgid}/collection/${id}`;
  for (const [path, noun] of [
    ["item", "variable"],
    ["reference", "reference"],
  ]) {
    const response = await axiosInstance.get(`${base}/${path}`);
    const results = await Promise.allSettled(
      (response.data.data || []).map((child: { id: string }) => axiosInstance.delete(`${base}/${path}/${child.id}`))
    );
    const failures = results.filter((r) => r.status === "rejected").length;
    if (failures > 0) {
      message.warning(`${failures} ${noun}(s) failed to delete`);
    }
  }
  await axiosInstance.delete(base);
};
