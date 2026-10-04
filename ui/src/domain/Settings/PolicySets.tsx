import React, { useEffect, useMemo, useState } from "react";
import { Button, List, Tabs, message } from "antd";
import { PlusOutlined } from "@ant-design/icons";
import { useNavigate, useParams, useSearchParams } from "react-router-dom";
import axiosInstance, { getErrorMessage } from "../../config/axiosConfig";
import { SettingsPageHeader } from "@/components/settings/SettingsPageHeader";
import { Loading } from "@/components/feedback/Loading";
import { EmptyState } from "@/components/feedback/EmptyState";
import DeleteConfirmationModal from "@/components/modals/DeleteConfirmationModal/DeleteConfirmationModal";
import { CreateEditPolicySet } from "./CreateEditPolicySet";
import { PolicyExemptionsSettings } from "./PolicyExemptions";
import {
  PolicySetCard,
  PolicySetFilter,
  PolicySetTable,
  PolicySetsViewMode,
  getStoredPolicySetsViewMode,
} from "./components";
import "./Settings.css";
import "./PolicySets.css";

type Props = {
  editorMode?: "new" | "edit";
  editorId?: string;
  managePermission?: boolean;
};

export const PolicySetsSettings: React.FC<Props> = ({ editorMode, editorId, managePermission = true }) => {
  const { orgid } = useParams();
  const navigate = useNavigate();
  const [searchParams, setSearchParams] = useSearchParams();
  const activeTab = searchParams.get("tab") || "sets";

  const [policySets, setPolicySets] = useState<any[]>([]);
  const [attachmentCounts, setAttachmentCounts] = useState<Record<string, number>>({});
  const [loading, setLoading] = useState(true);
  const [pendingDelete, setPendingDelete] = useState<any | null>(null);

  // Filter & Search states
  const [searchQuery, setSearchQuery] = useState("");
  const [categoryFilter, setCategoryFilter] = useState("ALL");
  const [scopeFilter, setScopeFilter] = useState("ALL");
  const [viewMode, setViewMode] = useState<PolicySetsViewMode>(getStoredPolicySetsViewMode);

  // Pagination states
  const [currentPage, setCurrentPage] = useState(1);
  const [pageSize, setPageSize] = useState(10);
  const [notificationConfigs, setNotificationConfigs] = useState<
    Record<string, { id: string; name: string; channelType: string }>
  >({});

  const loadPolicySets = async () => {
    setLoading(true);
    try {
      const res = await axiosInstance.get(`policy_set?include=attachments,notificationConfiguration`);
      const items = res.data?.data || [];
      // Filter by organization if needed
      const orgItems = items.filter((item: any) => {
        const orgRel = item.relationships?.organization?.data;
        return !orgRel || orgRel.id === orgid;
      });
      setPolicySets(orgItems);

      // Map attachment counts
      const counts: Record<string, number> = {};
      orgItems.forEach((p: any) => {
        const atts = p.relationships?.attachments?.data;
        counts[p.id] = Array.isArray(atts) ? atts.length : 0;
      });
      setAttachmentCounts(counts);

      // Map included notification configurations
      const included = res.data?.included || [];
      const notifMap: Record<string, { id: string; name: string; channelType: string }> = {};
      included.forEach((inc: any) => {
        if (inc.type === "notification_configuration") {
          notifMap[inc.id] = {
            id: inc.id,
            name: inc.attributes?.name || inc.id,
            channelType: inc.attributes?.channelType || "",
          };
        }
      });
      setNotificationConfigs(notifMap);
    } catch (err: any) {
      message.error(getErrorMessage(err));
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    if (!editorMode) {
      void loadPolicySets();
    }
  }, [editorMode, orgid]);

  const handleDelete = async (id: string) => {
    try {
      await axiosInstance.delete(`policy_set/${id}`);
      message.success("Policy set deleted successfully");
      setPendingDelete(null);
      void loadPolicySets();
    } catch (err: any) {
      message.error(getErrorMessage(err));
    }
  };

  const filteredPolicySets = useMemo(() => {
    return policySets.filter((item: any) => {
      const attrs = item.attributes || {};
      const name = (attrs.name || "").toLowerCase();
      const desc = (attrs.description || "").toLowerCase();
      const repo = (attrs.repository || "").toLowerCase();
      const q = searchQuery.trim().toLowerCase();

      // 1. Search by name, description, or repository
      if (q && !name.includes(q) && !desc.includes(q) && !repo.includes(q)) {
        return false;
      }

      // 2. Filter by policy category / enforcement level
      if (categoryFilter !== "ALL") {
        if ((attrs.enforcementLevel || "").toUpperCase() !== categoryFilter) {
          return false;
        }
      }

      // 3. Filter by scope (Global vs Attached)
      if (scopeFilter === "GLOBAL" && !attrs.global) {
        return false;
      }
      if (scopeFilter === "ATTACHED" && attrs.global) {
        return false;
      }

      return true;
    });
  }, [policySets, searchQuery, categoryFilter, scopeFilter]);

  const hasActiveFilters = searchQuery.trim() !== "" || categoryFilter !== "ALL" || scopeFilter !== "ALL";

  const handleResetFilters = () => {
    setSearchQuery("");
    setCategoryFilter("ALL");
    setScopeFilter("ALL");
    setCurrentPage(1);
  };

  const handleSearchChange = (val: string) => {
    setSearchQuery(val);
    setCurrentPage(1);
  };

  const handleCategoryChange = (val: string) => {
    setCategoryFilter(val);
    setCurrentPage(1);
  };

  const handleScopeChange = (val: string) => {
    setScopeFilter(val);
    setCurrentPage(1);
  };

  const handlePageChange = (page: number, size: number) => {
    setCurrentPage(page);
    setPageSize(size);
  };

  if (editorMode === "new") {
    return <CreateEditPolicySet mode="create" managePermission={managePermission} />;
  }

  if (editorMode === "edit") {
    return <CreateEditPolicySet mode="edit" policySetId={editorId} managePermission={managePermission} />;
  }

  const openCreate = () => navigate(`/organizations/${orgid}/settings/policies/new`);
  const openEdit = (id: string) => navigate(`/organizations/${orgid}/settings/policies/edit/${id}`);

  const renderPolicySets = () => {
    if (loading) {
      return <Loading loading description="Loading policy sets..." />;
    }
    if (policySets.length === 0) {
      return (
        <EmptyState simple description="No policy sets yet. Create one to check plans against OPA policies.">
          {managePermission && (
            <Button icon={<PlusOutlined />} onClick={openCreate}>
              Create policy set
            </Button>
          )}
        </EmptyState>
      );
    }
    return (
      <>
        <PolicySetFilter
          searchQuery={searchQuery}
          onSearchChange={handleSearchChange}
          categoryFilter={categoryFilter}
          onCategoryChange={handleCategoryChange}
          scopeFilter={scopeFilter}
          onScopeChange={handleScopeChange}
          viewMode={viewMode}
          onViewModeChange={setViewMode}
          onResetFilters={handleResetFilters}
          hasActiveFilters={hasActiveFilters}
        />

        {filteredPolicySets.length === 0 ? (
          <EmptyState simple description="No policy sets match these filters.">
            <Button onClick={handleResetFilters} data-testid="empty-clear-filters-btn">
              Clear filters
            </Button>
          </EmptyState>
        ) : viewMode === "compact" ? (
          <PolicySetTable
            policySets={filteredPolicySets}
            attachmentCounts={attachmentCounts}
            managePermission={managePermission}
            onEdit={openEdit}
            onDelete={(item) => setPendingDelete(item)}
            orgid={orgid!}
            currentPage={currentPage}
            pageSize={pageSize}
            onPageChange={handlePageChange}
            notificationConfigs={notificationConfigs}
          />
        ) : (
          <List
            dataSource={filteredPolicySets}
            pagination={{
              current: currentPage,
              pageSize: pageSize,
              total: filteredPolicySets.length,
              showSizeChanger: true,
              pageSizeOptions: ["10", "20", "50"],
              onChange: handlePageChange,
              showTotal: (total, range) => `${range[0]}-${range[1]} of ${total} policy sets`,
            }}
            renderItem={(item) => {
              const notifId = item.relationships?.notificationConfiguration?.data?.id;
              return (
                <PolicySetCard
                  key={item.id}
                  item={item}
                  attachmentsCount={attachmentCounts[item.id] ?? 0}
                  managePermission={managePermission}
                  onEdit={openEdit}
                  onDelete={(item) => setPendingDelete(item)}
                  orgid={orgid!}
                  notificationConfig={notifId ? notificationConfigs[notifId] : undefined}
                />
              );
            }}
          />
        )}
      </>
    );
  };

  return (
    <div>
      <SettingsPageHeader
        title="Policy sets"
        description="Check every plan against OPA policies, and waive specific rules with exemptions."
        divider={false}
        actions={
          activeTab === "sets" ? (
            <Button
              type="primary"
              icon={<PlusOutlined />}
              onClick={openCreate}
              disabled={!managePermission}
              data-testid="add-policy-set-btn"
            >
              Create policy set
            </Button>
          ) : null
        }
      />

      <Tabs
        activeKey={activeTab}
        onChange={(key) => setSearchParams({ tab: key })}
        items={[
          {
            key: "sets",
            label: `Policy sets (${policySets.length})`,
            children: renderPolicySets(),
          },
          {
            key: "exemptions",
            label: "Exemptions",
            children: <PolicyExemptionsSettings managePermission={managePermission} />,
          },
        ]}
      />

      <DeleteConfirmationModal
        open={pendingDelete !== null}
        title="Delete policy set"
        message={`Workspaces will no longer be checked against ${pendingDelete?.attributes?.name}. Its attachments, parameters and exemptions are deleted too. This cannot be undone.`}
        confirmValue={pendingDelete?.attributes?.name}
        okText="Delete policy set"
        onConfirm={() => handleDelete(pendingDelete.id)}
        onCancel={() => setPendingDelete(null)}
      />
    </div>
  );
};
