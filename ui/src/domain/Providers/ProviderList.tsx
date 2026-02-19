import { CloudOutlined, LinkOutlined } from "@ant-design/icons";
import { Card, Empty, List, Space, Typography } from "antd";
import { useMemo } from "react";
import { useNavigate, useParams } from "react-router-dom";
import { FlatProvider } from "./types";
import "../Modules/Module.css";

type Params = {
  orgid: string;
};

type Props = {
  providers: FlatProvider[];
  searchFilter: string;
};

/**
 * Extract a source repo owner/repo string and URL from a description that
 * may contain an embedded URL (e.g. "https://github.com/owner/repo").
 */
const extractSourceRepo = (
  description: string
): { repoLabel: string; repoUrl: string } | null => {
  const urlMatch = description.match(/https?:\/\/[^\s]+/);
  if (!urlMatch) return null;
  const url = urlMatch[0];
  try {
    const repoName = new URL(url).pathname
      .replace(/^\//, "")
      .replace(/\.git$/, "");
    if (repoName) return { repoLabel: repoName, repoUrl: url };
  } catch {
    /* invalid URL – ignore */
  }
  return null;
};

export const ProviderList = ({ providers, searchFilter }: Props) => {
  const { orgid } = useParams<Params>();
  const navigate = useNavigate();

  const filteredProviders = useMemo(() => {
    if (searchFilter === "") {
      return providers;
    }
    return providers.filter(
      (provider) =>
        provider.name.toLowerCase().includes(searchFilter.toLowerCase()) ||
        provider.description?.toLowerCase().includes(searchFilter.toLowerCase())
    );
  }, [searchFilter, providers]);

  if (filteredProviders.length === 0) {
    return (
      <Empty
        description={
          searchFilter
            ? "No providers match your search"
            : "No providers found in this organization"
        }
      />
    );
  }

  return (
    <List
      split={false}
      dataSource={filteredProviders}
      pagination={{ defaultPageSize: 5, showTotal: (total, range) => `${range[0]} - ${range[1]} of ${total}` }}
      renderItem={(item) => {
        const desc = item.description || "";
        const source = extractSourceRepo(desc);
        const descriptionText = source
          ? desc.replace(source.repoUrl, "").replace(/Source:?\s*/i, "").trim()
          : desc.replace(/Source:?\s*/i, "").trim();

        return (
          <List.Item
            style={{ cursor: "pointer", padding: "6px 0" }}
            onClick={() =>
              navigate(
                `/organizations/${orgid}/registry/providers/${item.id}`
              )
            }
          >
            <Card
              hoverable
              className="module-card"
              style={{ width: "100%" }}
              styles={{ body: { padding: 0 } }}
            >
              <div className="module-card-body">
                <div style={{ display: "flex", gap: 14, alignItems: "flex-start" }}>
                  <div style={{ flexShrink: 0, width: 36, height: 36, display: "flex", alignItems: "center", justifyContent: "center" }}>
                    <CloudOutlined style={{ fontSize: 18, color: "#7b61ff" }} />
                  </div>
                  <div style={{ flex: 1, minWidth: 0 }}>
                    <Typography.Text strong style={{ fontSize: 16, color: "#222b3d" }}>
                      {item.name}
                    </Typography.Text>
                    <div className="module-card-desc">
                      {descriptionText || "No description provided for this provider"}
                    </div>
                  </div>
                </div>
              </div>
              <div style={{ borderTop: "1px solid #f0f0f0", padding: "10px 24px", display: "flex", justifyContent: "space-between", alignItems: "center" }}>
                <Space size={16}>
                  {item.latestVersion && (
                    <Typography.Text style={{ fontSize: 13, color: "#8c97a8" }}>
                      v{item.latestVersion}
                    </Typography.Text>
                  )}
                </Space>
                <Space size={6}>
                  {source && (
                    <Typography.Link
                      href={source.repoUrl}
                      target="_blank"
                      rel="noopener noreferrer"
                      onClick={(e) => e.stopPropagation()}
                      style={{ fontSize: 13 }}
                    >
                      <LinkOutlined style={{ marginRight: 3 }} />
                      {source.repoLabel}
                    </Typography.Link>
                  )}
                  <Typography.Text style={{ fontSize: 13, color: "#8c97a8" }}>provider</Typography.Text>
                </Space>
              </div>
            </Card>
          </List.Item>
        );
      }}
    />
  );
};

export default ProviderList;
