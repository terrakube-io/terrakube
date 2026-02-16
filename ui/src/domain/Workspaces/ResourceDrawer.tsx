import { Avatar, Col, Drawer, Row, Space, Spin, Tabs } from "antd";
import { useEffect, useState } from "react";
import ActionLoader from "../../ActionLoader.js";
import axiosInstance from "../../config/axiosConfig";
import { ActionWithSettings, Resource, Workspace } from "../types.js";
import { getServiceIcon } from "./Icons.jsx";

type Props = {
  open: boolean;
  setOpen: (val: boolean) => void;
  resource?: Resource;
  workspace: Workspace;
};

export const ResourceDrawer = ({ open, resource, setOpen, workspace }: Props) => {
  const [drawerOpen, setDrawerOpen] = useState(open);
  const [actions, setActions] = useState<ActionWithSettings[]>([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    const fetchActions = async () => {
      try {
        setLoading(true);
        const response = await axiosInstance.get('action', {
          params: {
            'filter[action]':
              "active==true;type=in=('Workspace/ResourceDrawer/Action','Workspace/ResourceDrawer/Tab')",
          },
        });
        const fetchedActions = response.data.data || [];

        // Filter actions and attach settings to each action
        const filteredActions = fetchedActions.reduce((acc: any, action: ActionWithSettings) => {
          if (!action.attributes.displayCriteria) {
            acc.push(action);
            return acc;
          }

          let displayCriteria;
          try {
            displayCriteria = JSON.parse(action.attributes.displayCriteria);
          } catch (error) {
            console.error("Error parsing displayCriteria JSON:", error);
            return acc;
          }

          for (const criteria of displayCriteria) {
            const settings = evaluateCriteria(criteria, {
              state: resource,
              apiUrl: new URL(window._env_.REACT_APP_TERRAKUBE_API_URL).origin,
            });
            if (settings) {
              action.settings = settings; // Attach settings to the action

              acc.push(action);
              break;
            }
          }

          return acc;
        }, []);

        setActions(filteredActions);
      } catch (error) {
        console.error("Error fetching actions:", error);
      } finally {
        setLoading(false);
      }
    };

    fetchActions();
    if (!open) {
      setDrawerOpen(false);
    } else {
      setDrawerOpen(true);
    }
  }, [open, resource]);

  const onClose = () => {
    setOpen(false);
    setDrawerOpen(false);
  };

  const context = {
    state: resource,
    apiUrl: new URL(window._env_.REACT_APP_TERRAKUBE_API_URL).origin,
    workspace: workspace,
  };

  // Function to evaluate display criteria and return settings
  const evaluateCriteria = (criteria: any, _: any) => {
    try {
      const result = eval(criteria.filter);
      if (result) {
        if (!criteria.settings) {
          return {};
        }
        return criteria.settings.reduce((acc: any, setting: any) => {
          acc[setting.key] = setting.value;
          return acc;
        }, {});
      }
    } catch (error) {
      console.error("Error evaluating criteria:", error);
    }
    return null;
  };

  return (
    <Drawer
      width={640}
      title={
        resource && (
          <>
            <Avatar shape="square" size="small" src={getServiceIcon(resource.provider, resource.type)} />{" "}
            {resource.name}
          </>
        )
      }
      placement="right"
      onClose={onClose}
      open={drawerOpen}
    >
      {loading ? (
        <Spin tip="Loading...">
          <Space size={10} style={{ width: "100%" }} direction="vertical" />
        </Spin>
      ) : (
        <Space size={10} style={{ width: "100%" }} direction="vertical">
          <Row>
            <Col span={24}>
              <Space size={5} direction="horizontal">
                {actions &&
                  actions
                    .filter((action) => action?.attributes.type === "Workspace/ResourceDrawer/Action")
                    .map((action, index) => (
                      <ActionLoader
                        key={index}
                        action={action?.attributes.action}
                        context={{ ...context, settings: action.settings }}
                      />
                    ))}
              </Space>
            </Col>
          </Row>

          <Tabs
            defaultActiveKey="1"
            items={[
              ...actions
                .filter((action) => action?.attributes.type === "Workspace/ResourceDrawer/Tab")
                .map((action, index) => ({
                  key: `tab-${index + 1}`,
                  label: action.attributes.label,
                  children: (
                    <ActionLoader
                      key={index}
                      action={action?.attributes.action}
                      context={{ ...context, settings: action.settings }}
                    />
                  ),
                })),
            ]}
          />
        </Space>
      )}
    </Drawer>
  );
};
