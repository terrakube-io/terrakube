import { ErrorInformation } from "@/modules/api/types";
import { Breadcrumb, Typography, Flex, Layout, Skeleton, Spin } from "antd";
import { ErrorState } from "@/components/feedback/ErrorState";
import "./PageWrapper.css";
import { NavLink, useLocation } from "react-router-dom";
import { useEffect } from "react";
import clsx from "classnames";
import { getOrgIdFromPathname } from "@/config/orgId";
import { useOrganizationName } from "@/hooks/useOrganizationName";

const { Content } = Layout;

export type PageWidth = "fluid" | "reading" | "form";

type Props = {
  title: string;
  subTitle?: string;
  children: any;
  error?: ErrorInformation | string;
  loading?: boolean;
  loadingText?: string;
  /** On organization URLs the first crumb is the organization: its label is resolved from the id in the URL. */
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
  const orgId = getOrgIdFromPathname(useLocation().pathname);
  const orgName = useOrganizationName(orgId);

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
          items={breadcrumbs.map((bc, index) => {
            const isOrgCrumb = orgId && index === 0;
            if (isOrgCrumb && !orgName) {
              return {
                key: "organization",
                title: <Skeleton.Input active size="small" className="page-wrapper-crumb-skeleton" />,
              };
            }
            const label = isOrgCrumb ? orgName! : bc.label;
            return {
              key: bc.path ?? label,
              title: bc.path ? <NavLink to={bc.path}>{label}</NavLink> : label,
            };
          })}
        />
      )}
      <div className="page-wrapper-content">
        <div className={clsx("page-wrapper-inner", `page-wrapper-inner-${width}`)}>
          {(showTitle || actions) && (
            <Flex justify="space-between" flex={1} wrap>
              <div>
                {showTitle && <Typography.Title className="page-wrapper-title">{title}</Typography.Title>}
                {showTitle && subTitle && <Typography.Text type="secondary">{subTitle}</Typography.Text>}
              </div>
              {actions && <div className="page-wrapper-actions">{actions}</div>}
            </Flex>
          )}

          {errorInfo && (
            <ErrorState title={errorInfo.title} message={errorInfo.message} onRetry={() => window.location.reload()} />
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
