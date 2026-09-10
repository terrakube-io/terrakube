import React, { useEffect, useMemo, useState } from "react";
import { Button, Card, Space, Typography, message } from "antd";
import { PlusOutlined, SafetyCertificateOutlined } from "@ant-design/icons";
import { useParams } from "react-router-dom";
import axiosInstance, { getErrorMessage } from "../../config/axiosConfig";
import {
  ExemptionRecord,
  ExemptionScopeFilter,
  ExemptionStatusFilter,
  PolicyExemptionFilter,
  PolicyExemptionModal,
  PolicyExemptionTable,
} from "./components";

const { Paragraph } = Typography;

type Props = {
  managePermission?: boolean;
};

export const PolicyExemptionsSettings: React.FC<Props> = ({
  managePermission = true,
}) => {
  const { orgid } = useParams<{ orgid: string }>();

  const [rawExemptions, setRawExemptions] = useState<any[]>([]);
  const [includedMap, setIncludedMap] = useState<Record<string, Record<string, any>>>({});
  const [policySets, setPolicySets] = useState<Array<{ id: string; name: string }>>([]);
  const [loading, setLoading] = useState(true);

  // Filter states
  const [searchQuery, setSearchQuery] = useState("");
  const [statusFilter, setStatusFilter] = useState<ExemptionStatusFilter>("ALL");
  const [scopeFilter, setScopeFilter] = useState<ExemptionScopeFilter>("ALL");
  const [policySetFilter, setPolicySetFilter] = useState("ALL");

  // Modal states
  const [modalVisible, setModalVisible] = useState(false);
  const [modalMode, setModalMode] = useState<"create" | "edit">("create");
  const [editingExemption, setEditingExemption] = useState<any | null>(null);

  const loadExemptions = async () => {
    if (!orgid) return;
    setLoading(true);
    try {
      // 1. Fetch policy sets for policySet filter dropdown
      try {
        const psRes = await axiosInstance.get("policy_set");
        const allPs = psRes.data?.data || [];
        const orgPs = allPs.filter((p: any) => {
          const orgRel = p.relationships?.organization?.data;
          return !orgRel || orgRel.id === orgid;
        });
        setPolicySets(
          orgPs.map((p: any) => ({
            id: p.id,
            name: p.attributes?.name || p.id,
          }))
        );
      } catch {
        // Fallback if policy set query fails
      }

      // 2. Fetch exemptions with included relationships
      const res = await axiosInstance.get(
        `organization/${orgid}/policyExemption?include=policySet,workspace,project`
      );

      const items = res.data?.data || [];
      const included = res.data?.included || [];

      // Build quick lookup map for included resources by type:id
      const incMap: Record<string, Record<string, any>> = {};
      included.forEach((inc: any) => {
        if (!incMap[inc.type]) {
          incMap[inc.type] = {};
        }
        incMap[inc.type][inc.id] = inc.attributes;
      });

      setIncludedMap(incMap);
      setRawExemptions(items);
    } catch (err: any) {
      message.error(getErrorMessage(err) || "Failed to load policy exemptions");
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    void loadExemptions();
  }, [orgid]);

  // Transform raw Elide exemptions to UI records
  const exemptionRecords = useMemo<ExemptionRecord[]>(() => {
    return rawExemptions.map((item: any) => {
      const attrs = item.attributes || {};
      const rels = item.relationships || {};

      const psId = rels.policySet?.data?.id;
      const wsId = rels.workspace?.data?.id;
      const projId = rels.project?.data?.id;

      const psName =
        includedMap["policy_set"]?.[psId]?.name ||
        policySets.find((p) => p.id === psId)?.name ||
        psId ||
        "Policy Set";
      const wsName = includedMap["workspace"]?.[wsId]?.name;
      const projName = includedMap["project"]?.[projId]?.name;

      let scopeType: "ORGANIZATION" | "PROJECT" | "WORKSPACE" = "ORGANIZATION";
      if (wsId) {
        scopeType = "WORKSPACE";
      } else if (projId) {
        scopeType = "PROJECT";
      }

      return {
        id: item.id,
        ruleId: attrs.ruleId || "",
        policySetId: psId,
        policySetName: psName,
        ticketReference: attrs.ticketReference || "",
        justification: attrs.justification || "",
        expiresAt: attrs.expiresAt || null,
        scopeType,
        workspaceId: wsId,
        workspaceName: wsName,
        projectId: projId,
        projectName: projName,
        createdDate: attrs.createdDate,
        createdBy: attrs.createdBy,
      };
    });
  }, [rawExemptions, includedMap, policySets]);

  // Compute status counts
  const statusCounts = useMemo(() => {
    let all = 0;
    let active = 0;
    let expiringSoon = 0;
    let expired = 0;
    let indefinite = 0;

    const now = new Date();

    exemptionRecords.forEach((record) => {
      all++;
      if (!record.expiresAt) {
        indefinite++;
        active++;
      } else {
        const expDate = new Date(record.expiresAt);
        const diffDays = Math.ceil((expDate.getTime() - now.getTime()) / (1000 * 60 * 60 * 24));
        if (diffDays < 0) {
          expired++;
        } else if (diffDays <= 7) {
          expiringSoon++;
          active++;
        } else {
          active++;
        }
      }
    });

    return { all, active, expiringSoon, expired, indefinite };
  }, [exemptionRecords]);

  // Filtered records
  const filteredRecords = useMemo(() => {
    const now = new Date();

    return exemptionRecords.filter((rec) => {
      // 1. Status filter
      if (statusFilter === "INDEFINITE") {
        if (rec.expiresAt) return false;
      } else if (statusFilter === "EXPIRED") {
        if (!rec.expiresAt) return false;
        const diffDays = Math.ceil((new Date(rec.expiresAt).getTime() - now.getTime()) / (1000 * 60 * 60 * 24));
        if (diffDays >= 0) return false;
      } else if (statusFilter === "EXPIRING_SOON") {
        if (!rec.expiresAt) return false;
        const diffDays = Math.ceil((new Date(rec.expiresAt).getTime() - now.getTime()) / (1000 * 60 * 60 * 24));
        if (diffDays < 0 || diffDays > 7) return false;
      } else if (statusFilter === "ACTIVE") {
        if (rec.expiresAt) {
          const diffDays = Math.ceil((new Date(rec.expiresAt).getTime() - now.getTime()) / (1000 * 60 * 60 * 24));
          if (diffDays < 0) return false;
        }
      }

      // 2. Scope filter
      if (scopeFilter !== "ALL" && rec.scopeType !== scopeFilter) {
        return false;
      }

      // 3. Policy set filter
      if (policySetFilter !== "ALL" && rec.policySetId !== policySetFilter) {
        return false;
      }

      // 4. Text search
      if (searchQuery.trim()) {
        const q = searchQuery.toLowerCase();
        const matchesRule = rec.ruleId.toLowerCase().includes(q);
        const matchesTicket = rec.ticketReference.toLowerCase().includes(q);
        const matchesJustification = rec.justification.toLowerCase().includes(q);
        const matchesSetName = rec.policySetName.toLowerCase().includes(q);
        const matchesWs = rec.workspaceName?.toLowerCase().includes(q);
        const matchesProj = rec.projectName?.toLowerCase().includes(q);

        if (
          !matchesRule &&
          !matchesTicket &&
          !matchesJustification &&
          !matchesSetName &&
          !matchesWs &&
          !matchesProj
        ) {
          return false;
        }
      }

      return true;
    });
  }, [exemptionRecords, statusFilter, scopeFilter, policySetFilter, searchQuery]);

  const handleCreate = () => {
    setModalMode("create");
    setEditingExemption(null);
    setModalVisible(true);
  };

  const handleEdit = (record: ExemptionRecord) => {
    setModalMode("edit");
    setEditingExemption(record);
    setModalVisible(true);
  };

  const handleDelete = async (record: ExemptionRecord) => {
    try {
      await axiosInstance.delete(`policy_exemption/${record.id}`);
      message.success(`Policy exemption for ${record.ruleId} revoked successfully`);
      void loadExemptions();
    } catch (err: any) {
      message.error(getErrorMessage(err) || "Failed to revoke policy exemption");
    }
  };

  return (
    <div>
      <div
        style={{
          display: "flex",
          justifyContent: "space-between",
          alignItems: "center",
          marginBottom: 16,
        }}
      >
        <div>
          <Paragraph type="secondary" style={{ margin: 0 }}>
            Policy exemptions provide audited, time-bounded waivers for specific OPA rules across workspaces or projects.
          </Paragraph>
        </div>
        {managePermission && (
          <Button
            type="primary"
            icon={<PlusOutlined />}
            onClick={handleCreate}
            data-testid="add-exemption-btn"
          >
            Create Exemption
          </Button>
        )}
      </div>

      <Card>
        <PolicyExemptionFilter
          searchQuery={searchQuery}
          onSearchChange={setSearchQuery}
          statusFilter={statusFilter}
          onStatusFilterChange={setStatusFilter}
          scopeFilter={scopeFilter}
          onScopeFilterChange={setScopeFilter}
          policySetFilter={policySetFilter}
          onPolicySetFilterChange={setPolicySetFilter}
          policySets={policySets}
          statusCounts={statusCounts}
        />

        <PolicyExemptionTable
          items={filteredRecords}
          loading={loading}
          managePermission={managePermission}
          onEdit={handleEdit}
          onDelete={handleDelete}
        />
      </Card>

      {orgid && (
        <PolicyExemptionModal
          visible={modalVisible}
          mode={modalMode}
          initialData={
            editingExemption
              ? {
                  id: editingExemption.id,
                  policySetId: editingExemption.policySetId,
                  ruleId: editingExemption.ruleId,
                  scopeType: editingExemption.scopeType,
                  workspaceId: editingExemption.workspaceId,
                  projectId: editingExemption.projectId,
                  ticketReference: editingExemption.ticketReference,
                  justification: editingExemption.justification,
                  expiresAt: editingExemption.expiresAt,
                }
              : undefined
          }
          organizationId={orgid}
          onCancel={() => setModalVisible(false)}
          onSuccess={() => {
            setModalVisible(false);
            void loadExemptions();
          }}
        />
      )}
    </div>
  );
};
