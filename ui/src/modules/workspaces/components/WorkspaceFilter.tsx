import {
  BarsOutlined,
  ExclamationCircleOutlined,
  StopOutlined,
  SyncOutlined,
  CheckCircleOutlined,
  CloseCircleOutlined,
  InfoCircleOutlined,
} from "@ant-design/icons";
import { Card, Row, Col, Segmented, Flex, Select, Input, theme } from "antd";
import { JobStatus } from "../../../domain/types";
import { useEffect, useMemo, useState } from "react";
import { WorkspaceListItem } from "@/modules/workspaces/types";
import organizationService from "@/modules/organizations/organizationService";
import useApiRequest from "@/modules/api/useApiRequest";
import { mapTag } from "@/modules/organizations/organizationMapper";
import { TagModel } from "@/modules/organizations/types";
import { WorkspaceSortOption, WORKSPACE_SORT_OPTIONS } from "../utils/workspaceSort";

type Props = {
  organizationId: string;
  workspaces: WorkspaceListItem[];
  onFiltered: (workspaces: WorkspaceListItem[]) => void;
  onTagsLoaded: (tags: TagModel[]) => void;
  sortOption: WorkspaceSortOption;
  onSortChange: (option: WorkspaceSortOption) => void;
};

enum Additional {
  All = "All",
  NeverExecuted = "NeverExecuted",
}

export default function WorkspaceFilter({
  workspaces,
  onFiltered,
  organizationId,
  onTagsLoaded,
  sortOption,
  onSortChange,
}: Props) {
  const {
    token: { colorBgContainer },
  } = theme.useToken();

  const [statusFilter, setStatusFilter] = useState<string>(sessionStorage.getItem("filterValue") || "All");
  const [searchFilter, setSearchFilter] = useState(sessionStorage.getItem("searchValue") || "");
  const [tagsFilter, setTagsFilter] = useState<string[]>((sessionStorage.getItem("selectedTags") as any) || []);
  const [tags, setTags] = useState<TagModel[]>([]);

  const { loading, execute } = useApiRequest({
    action: () => organizationService.listOrganizationTags(organizationId),
    onReturn: (data) => {
      const mapped = data.map(mapTag);
      setTags(mapped);
      onTagsLoaded(mapped);
    },
  });

  const options = useMemo(() => {
    return tags.map((t) => ({ label: t.name, value: t.id }));
  }, [tags]);

  function filterItems(isClear?: boolean) {
    let internalSearchFilter = searchFilter;
    if (isClear) internalSearchFilter = "";

    let filteredWorkspaces =
      statusFilter === Additional.All ? workspaces : workspaces.filter((x) => x.lastStatus === statusFilter);

    filteredWorkspaces = filteredWorkspaces.filter((workspace) => {
      if (workspace.description) {
        return workspace.name.includes(internalSearchFilter) || workspace.description?.includes(internalSearchFilter);
      } else {
        return workspace.name.includes(internalSearchFilter);
      }
    });

    filteredWorkspaces = filteredWorkspaces.filter((workspace) => {
      if (tagsFilter && tagsFilter.length > 0) {
        return workspace.tags?.some((tag) => tagsFilter.includes(tag));
      } else {
        return true;
      }
    });

    onFiltered(filteredWorkspaces);
  }

  useEffect(() => {
    filterItems();
  }, [statusFilter, tagsFilter]);
  useEffect(() => {
    execute();
  }, []);

  return (
    <Card
      style={{ marginTop: "10px", background: colorBgContainer }}
      styles={{
        body: {
          padding: "5px 10px",
        },
      }}
    >
      <Row justify="end" gutter={[0, 5]}>
        <Col span={24} xxl={16}>
          <div className="workspaceFilterSegmentedWrapper">
            <Segmented
              onChange={setStatusFilter}
              value={statusFilter}
              options={[
                {
                  label: "All",
                  value: Additional.All,
                  icon: <BarsOutlined />,
                },
                {
                  label: "Awaiting approval",
                  value: JobStatus.WaitingApproval,
                  icon: <ExclamationCircleOutlined style={{ color: "#fa8f37" }} />,
                },
                {
                  label: "Failed",
                  value: JobStatus.Failed,
                  icon: <StopOutlined style={{ color: "#FB0136" }} />,
                },
                {
                  label: "Rejected",
                  value: JobStatus.Rejected,
                  icon: <CloseCircleOutlined style={{ color: "#FB0136" }} />,
                },
                {
                  label: "Running",
                  value: JobStatus.Running,
                  icon: <SyncOutlined style={{ color: "#108ee9" }} />,
                },
                {
                  label: "Completed",
                  value: JobStatus.Completed,
                  icon: <CheckCircleOutlined style={{ color: "#2eb039" }} />,
                },
                {
                  label: "Never Executed",
                  value: Additional.NeverExecuted,
                  icon: <InfoCircleOutlined />,
                },
              ]}
            />
          </div>
        </Col>

        <Col span={24} xxl={8}>
          <Flex gap="middle">
            <Select
              mode="multiple"
              showSearch
              optionFilterProp="children"
              allowClear
              style={{ width: "100%" }}
              placeholder="Search by tag"
              options={options}
              maxTagCount="responsive"
              loading={loading}
              onChange={setTagsFilter}
              filterOption={(input, option) => (option?.label ?? "").toLowerCase().includes(input.toLowerCase())}
              filterSort={(optionA, optionB) =>
                (optionA?.label ?? "").toLowerCase().localeCompare((optionB?.label ?? "").toLowerCase())
              }
            />
            <Input.Search
              placeholder="Search by name, description"
              value={searchFilter}
              onChange={(e) => setSearchFilter(e.target.value)}
              onSearch={() => filterItems()}
              onClear={() => {
                filterItems(true);
              }}
              allowClear
            />
          </Flex>
        </Col>
      </Row>
      <Row style={{ marginTop: "5px" }}>
        <Col span={24} xl={8}>
          <Select
            prefix={<span style={{ marginRight: "5px" }}>Sort by:</span>}
            options={WORKSPACE_SORT_OPTIONS}
            value={sortOption}
            onChange={onSortChange}
            style={{ minWidth: 220 }}
            placeholder="Sort by"
          />
        </Col>
      </Row>
    </Card>
  );
}
