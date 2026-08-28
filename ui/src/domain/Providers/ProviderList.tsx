import { CloudOutlined, LinkOutlined } from "@ant-design/icons";
import { List, Typography } from "antd";
import { useMemo } from "react";
import { Link, useParams } from "react-router-dom";
import formatVersion from "@/modules/utils/formatVersion";
import { FlatProvider } from "./types";
import "../Modules/Module.css";
import { RegistryCard } from "@/components/RegistryCard";
import { EmptyState } from "@/components/EmptyState";

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
const extractSourceRepo = (description: string): { repoLabel: string; repoUrl: string } | null => {
  const urlMatch = description.match(/https?:\/\/[^\s]+/);
  if (!urlMatch) return null;
  const url = urlMatch[0];
  try {
    const repoName = new URL(url).pathname.replace(/^\//, "").replace(/\.git$/, "");
    if (repoName) return { repoLabel: repoName, repoUrl: url };
  } catch {
    /* invalid URL – ignore */
  }
  return null;
};

export const ProviderList = ({ providers, searchFilter }: Props) => {
  const { orgid } = useParams<Params>();

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
      <EmptyState
        simple
        description={searchFilter ? "No providers match your search" : "No providers found in this organization"}
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
          ? desc
              .replace(source.repoUrl, "")
              .replace(/Source:?\s*/i, "")
              .trim()
          : desc.replace(/Source:?\s*/i, "").trim();

        return (
          <List.Item style={{ padding: "6px 0" }}>
            <Link
              to={`/organizations/${orgid}/registry/providers/${item.id}`}
              style={{ display: "block", width: "100%", color: "inherit" }}
            >
              <RegistryCard
                icon={
                  <>
                    <CloudOutlined style={{ fontSize: 18, color: "#7b61ff" }} />
                  </>
                }
                title={item.name}
                description={descriptionText || "No description provided for this provider"}
                footerLeft={
                  item.latestVersion && (
                    <Typography.Text style={{ fontSize: 13, color: "var(--ant-color-text-secondary)" }}>
                      {formatVersion(item.latestVersion)}
                    </Typography.Text>
                  )
                }
                footerRight={
                  <>
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
                    <Typography.Text style={{ fontSize: 13, color: "var(--ant-color-text-secondary)" }}>
                      provider
                    </Typography.Text>
                  </>
                }
              />
            </Link>
          </List.Item>
        );
      }}
    />
  );
};

export default ProviderList;
