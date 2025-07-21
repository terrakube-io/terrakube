import PageWrapper from "@/modules/layout/PageWrapper/PageWrapper";
import { ClockCircleOutlined, CloudOutlined, CloudUploadOutlined, DownloadOutlined } from "@ant-design/icons";
import { Button, Card, Col, Input, List, Row, Space, Tag, Typography } from "antd";
import { DateTime } from "luxon";
import { useEffect, useState } from "react";
import { IconContext } from "react-icons";
import { FaAws } from "react-icons/fa";
import { MdBusiness } from "react-icons/md";
import { RiFolderHistoryLine } from "react-icons/ri";
import { VscAzure } from "react-icons/vsc";
import { Link, useNavigate, useParams } from "react-router-dom";
import { ORGANIZATION_ARCHIVE, ORGANIZATION_NAME } from "../../config/actionTypes";
import axiosInstance from "../../config/axiosConfig";
import { FlatModule, ModuleModel } from "../types";
import "./Module.css";

const include = { MODULE: "module" };
const { Search } = Input;

type Params = {
  orgid: string;
};

type Props = {
  organizationName: string;
  setOrganizationName: React.Dispatch<React.SetStateAction<string>>;
};

export const ModuleList = ({ setOrganizationName, organizationName }: Props) => {
  const { orgid } = useParams<Params>();
  const [modules, setModules] = useState<FlatModule[]>([]);
  const [filteredModules, setFilteredModules] = useState<FlatModule[]>([]);
  const [loading, setLoading] = useState(false);
  const navigate = useNavigate();

  const onSearch = (value: string) => {
    applyFilters(value);
  };

  const applyFilters = (searchValue: string) => {
    if (searchValue !== "") {
      const filteredModules = modules.filter((modules) => modules.name.includes(searchValue));
      setFilteredModules(filteredModules);
      return;
    }
    setFilteredModules(modules);
  };

  useEffect(() => {
    setLoading(true);
    sessionStorage.setItem(ORGANIZATION_ARCHIVE, orgid!);
    axiosInstance.get(`organization/${orgid}?include=module`).then((response) => {
      if (response.data.included) {
        setupOrganizationIncludes(response.data.included, setModules, setFilteredModules);
      }

      setLoading(false);
      sessionStorage.setItem(ORGANIZATION_NAME, response.data.data.attributes.name);
      setOrganizationName(response.data.data.attributes.name);
    });
  }, [orgid]);

  const renderLogo = (provider: string) => {
    switch (provider) {
      case "azurerm":
        return (
          <IconContext.Provider value={{ color: "#008AD7", size: "1.5em" }}>
            <VscAzure />
          </IconContext.Provider>
        );
      case "aws":
        return (
          <IconContext.Provider value={{ color: "#232F3E", size: "1.5em" }}>
            <FaAws />
          </IconContext.Provider>
        );
      default:
        return <CloudOutlined />;
    }
  };

  const handlePublish = () => {
    navigate("/organizations/" + orgid + "/registry/create");
  };

  return (
    <PageWrapper
      title="Modules"
      subTitle={`Modules in the ${organizationName} organization`}
      loadingText="Loading modules..."
      loading={loading}
      breadcrumbs={[
        { label: organizationName, path: "/" },
        { label: "Modules", path: `/organizations/${orgid}/registry` },
      ]}
      fluid
      actions={
        <Space>
          <Button type="primary" htmlType="button" icon={<CloudUploadOutlined />} onClick={handlePublish}>
            Publish module
          </Button>
        </Space>
      }
    >
      <div style={{ width: "100%", marginTop: "24px" }}>
        <Search
          placeholder="Filter modules"
          onSearch={onSearch}
          allowClear
          style={{ width: "100%", marginBottom: "16px" }}
        />
        <List
          split={false}
          dataSource={filteredModules}
          pagination={{ showSizeChanger: true, defaultPageSize: 10 }}
          renderItem={(item) => (
            <List.Item>
              <Link to={`/organizations/${orgid}/registry/${item.id}`} style={{ width: "100%" }}>
                <Card hoverable>
                  <Space style={{ width: "100%" }} direction="vertical">
                    <Row>
                      <Col span={24}>
                        <Typography.Title level={3}>{item.name}</Typography.Title>
                        <Typography.Text type="secondary">
                          {item.description || "No description provided for this module"}
                        </Typography.Text>
                      </Col>
                    </Row>
                    <Space size={40} style={{ marginTop: "25px" }}>
                      <Tag color="blue">
                        <Space size={4}>
                          <MdBusiness /> Private
                        </Space>
                      </Tag>
                      <Space>
                        {renderLogo(item.provider)}
                        <Typography.Text>{item.provider}</Typography.Text>
                      </Space>
                      <Space>
                        <IconContext.Provider value={{ size: "1.3em" }}>
                          <RiFolderHistoryLine />
                        </IconContext.Provider>
                        <Typography.Text>{item.latestVersion}</Typography.Text>
                      </Space>
                      <Space>
                        <ClockCircleOutlined />
                        <Typography.Text>
                          {item.createdDate ? DateTime.fromISO(item.createdDate).toRelative() : "Unknown"}
                        </Typography.Text>
                      </Space>
                      <Space>
                        <DownloadOutlined />
                        <Typography.Text>{item.downloadQuantity}</Typography.Text>
                      </Space>
                    </Space>
                  </Space>
                </Card>
              </Link>
            </List.Item>
          )}
        />
      </div>
    </PageWrapper>
  );
};

function setupOrganizationIncludes(
  includes: ModuleModel[],
  setModules: React.Dispatch<React.SetStateAction<FlatModule[]>>,
  setFilteredModules: React.Dispatch<React.SetStateAction<FlatModule[]>>
) {
  const modules: FlatModule[] = [];

  includes.forEach((element: ModuleModel) => {
    switch (element.type) {
      case include.MODULE:
        modules.push({
          id: element.id,
          ...element.attributes,
        });
        break;
      default:
        break;
    }
  });

  setModules(modules);
  setFilteredModules(modules);
}
