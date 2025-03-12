import { ErrorInformation } from "@/modules/api/types";
import { Breadcrumb, Typography, Alert, Flex, Spin, theme } from "antd";
import { Content } from "antd/es/layout/layout";
import "./PageWrapper.css";
import { NavLink } from "react-router-dom";
import clsx from "classnames";

type Props = {
  title: string;
  subTitle?: string;
  children: any;
  error?: ErrorInformation;
  loading?: boolean;
  loadingText?: string;
  breadcrumbs?: {
    label: string;
    path: string;
  }[];
  actions?: React.ReactNode;
  fluid?: boolean;
};

export default function PageWrapper({
  children,
  error,
  loading,
  loadingText,
  title,
  subTitle,
  breadcrumbs,
  actions,
  fluid,
}: Props) {
  const {
    token: { colorBgContainer },
  } = theme.useToken();

  return (
    <Content className="page-wrapper">
      {breadcrumbs && (
        <Breadcrumb
          className="page-wrapper-crumbs"
          items={breadcrumbs.map((bc) => ({
            key: bc.path,
            label: <NavLink to={bc.path}>{bc.label}</NavLink>,
          }))}
        />
      )}
      <div className="page-wrapper-content" style={{ background: colorBgContainer }}>
        <div
          className={clsx(
            "page-wrapper-inner",
            { "page-wrapper-inner-contained": !fluid },
            {
              "page-wrapper-inner-fluid": fluid,
            }
          )}
        >
          <Flex justify="space-between" flex={1}>
            <div>
              <Typography.Title className="page-wrapper-title">{title}</Typography.Title>
              {subTitle && <Typography.Text type="secondary">{subTitle}</Typography.Text>}
            </div>
            {actions}
          </Flex>

          {error && <Alert className="page-wrapper-alert" message={error.title} type="error" showIcon banner />}

          {loading ? (
            <Flex align="center" className="page-wrapper-loader" vertical gap="middle">
              <Spin size="large" />
              <Typography.Text>{loadingText || "Loading..."}</Typography.Text>
            </Flex>
          ) : (
            children
          )}
        </div>
      </div>
    </Content>
  );
}
