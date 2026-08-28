import { ErrorInformation } from "@/modules/api/types";
import { Breadcrumb, Typography, Alert, Flex, Spin, theme } from "antd";
import { Content } from "antd/es/layout/layout";
import "./PageWrapper.css";
import { NavLink } from "react-router-dom";
import { useEffect } from "react";
import clsx from "classnames";

export type PageWidth = "fluid" | "reading" | "form";

type Props = {
  title: string;
  subTitle?: string;
  children: any;
  error?: ErrorInformation | string;
  loading?: boolean;
  loadingText?: string;
  breadcrumbs?: {
    label: string;
    path?: string;
  }[];
  actions?: React.ReactNode;
  width?: PageWidth;
  showTitle?: boolean;
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
  width = "fluid",
  showTitle = true,
}: Props) {
  const {
    token: { colorBgContainer },
  } = theme.useToken();

  useEffect(() => {
    document.title = title ? `${title} · Terrakube` : "Terrakube";
    return () => {
      document.title = "Terrakube";
    };
  }, [title]);

  const errorInfo: ErrorInformation | undefined =
    typeof error === "string" ? { title: "Something went wrong", message: error } : error;

  return (
    <Content className="page-wrapper">
      {breadcrumbs && (
        <Breadcrumb
          className="page-wrapper-crumbs"
          items={breadcrumbs.map((bc) => ({
            key: bc.path ?? bc.label,
            title: bc.path ? <NavLink to={bc.path}>{bc.label}</NavLink> : bc.label,
          }))}
        />
      )}
      <div className="page-wrapper-content" style={{ background: colorBgContainer }}>
        <div className={clsx("page-wrapper-inner", `page-wrapper-inner-${width}`)}>
          {(showTitle || actions) && (
            <Flex justify="space-between" flex={1} wrap>
              <div>
                {showTitle && <Typography.Title className="page-wrapper-title">{title}</Typography.Title>}
                {showTitle && subTitle && <Typography.Text type="secondary">{subTitle}</Typography.Text>}
              </div>
              {actions}
            </Flex>
          )}

          {errorInfo && (
            <Alert
              className="page-wrapper-alert"
              title={errorInfo.title}
              description={errorInfo.message}
              type="error"
              showIcon
              banner
            />
          )}

          {loading ? (
            <Flex align="center" className="page-wrapper-loader" vertical gap="middle">
              <Spin size="large" />
              <Typography.Text>{loadingText || "Loading..."}</Typography.Text>
            </Flex>
          ) : (
            !errorInfo && children
          )}
        </div>
      </div>
    </Content>
  );
}
