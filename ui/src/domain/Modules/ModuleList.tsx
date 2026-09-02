import { CloudOutlined, DownloadOutlined } from "@ant-design/icons";
import { List, Space, Typography } from "antd";
import { useMemo } from "react";
import { IconContext } from "react-icons";
import { FaAws } from "@/config/iconList";
import { VscAzure } from "react-icons/vsc";
import { Link, useParams } from "react-router-dom";
import { FlatModule } from "../types";
import "./Module.css";
import { RegistryCard } from "@/components/display/RegistryCard";

type Params = {
  orgid: string;
};

type Props = {
  modules: FlatModule[];
  searchFilter: string;
};

export const ModuleList = ({ modules, searchFilter }: Props) => {
  const { orgid } = useParams<Params>();

  const filteredModules = useMemo(() => {
    if (searchFilter === "") {
      return modules;
    }
    return modules.filter(
      (module) =>
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
        <List.Item style={{ padding: "6px 0" }}>
          <Link
            to={`/organizations/${orgid}/registry/${item.id}`}
            style={{ display: "block", width: "100%", color: "inherit" }}
          >
            <RegistryCard
              icon={renderLogo(item.provider)}
              title={item.name}
              description={item.description || "No description provided for this module"}
              footerLeft={
                <>
                  <Space size={4}>
                    <DownloadOutlined style={{ fontSize: 13, color: "var(--ant-color-text-secondary)" }} />
                    <Typography.Text style={{ fontSize: 13, color: "var(--ant-color-text-secondary)" }}>
                      {item.downloadQuantity}
                    </Typography.Text>
                  </Space>
                </>
              }
              footerRight={
                <>
                  {renderLogo(item.provider)}
                  <Typography.Text style={{ fontSize: 13, color: "var(--ant-color-text-secondary)" }}>
                    {item.provider}
                  </Typography.Text>
                </>
              }
            />
          </Link>
        </List.Item>
      )}
    />
  );
};
