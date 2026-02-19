import { CloudOutlined, DownloadOutlined } from "@ant-design/icons";
import { Card, List, Space, Typography } from "antd";
import { useMemo } from "react";
import { IconContext } from "react-icons";
import { FaAws } from "react-icons/fa";
import { VscAzure } from "react-icons/vsc";
import { useNavigate, useParams } from "react-router-dom";
import { FlatModule } from "../types";
import "./Module.css";

type Params = {
  orgid: string;
};

type Props = {
  modules: FlatModule[];
  searchFilter: string;
};

export const ModuleList = ({ modules, searchFilter }: Props) => {
  const { orgid } = useParams<Params>();
  const navigate = useNavigate();

  const filteredModules = useMemo(() => {
    if (searchFilter === "") {
      return modules;
    }
    return modules.filter((module) =>
      module.name.toLowerCase().includes(searchFilter.toLowerCase()) ||
      module.description?.toLowerCase().includes(searchFilter.toLowerCase())
    );
  }, [searchFilter, modules]);

  const renderLogo = (provider: string) => {
    switch (provider) {
      case "azurerm":
        return (
          <IconContext.Provider value={{ color: "#008AD7", size: "1.3em" }}>
            <VscAzure />
          </IconContext.Provider>
        );
      case "aws":
        return (
          <IconContext.Provider value={{ color: "#232F3E", size: "1.3em" }}>
            <FaAws />
          </IconContext.Provider>
        );
      default:
        return <CloudOutlined style={{ fontSize: 20, color: "#5b6b7f" }} />;
    }
  };

  return (
    <List
      split={false}
      dataSource={filteredModules}
      pagination={{ defaultPageSize: 5, showTotal: (total, range) => `${range[0]} - ${range[1]} of ${total}` }}
      renderItem={(item) => (
        <List.Item
          style={{ cursor: "pointer", padding: "6px 0" }}
          onClick={() =>
            navigate(`/organizations/${orgid}/registry/${item.id}`)
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
                  {renderLogo(item.provider)}
                </div>
                <div style={{ flex: 1, minWidth: 0 }}>
                  <Typography.Text strong style={{ fontSize: 16, color: "#222b3d" }}>
                    {item.name}
                  </Typography.Text>
                  <div className="module-card-desc">
                    {item.description || "No description provided for this module"}
                  </div>
                </div>
              </div>
            </div>
            <div style={{ borderTop: "1px solid #f0f0f0", padding: "10px 24px", display: "flex", justifyContent: "space-between", alignItems: "center" }}>
              <Space size={16}>
                <Space size={4}>
                  <DownloadOutlined style={{ fontSize: 13, color: "#8c97a8" }} />
                  <Typography.Text style={{ fontSize: 13, color: "#8c97a8" }}>
                    {item.downloadQuantity}
                  </Typography.Text>
                </Space>
              </Space>
              <Space size={6}>
                {renderLogo(item.provider)}
                <Typography.Text style={{ fontSize: 13, color: "#8c97a8" }}>{item.provider}</Typography.Text>
              </Space>
            </div>
          </Card>
        </List.Item>
      )}
    />
  );
};
