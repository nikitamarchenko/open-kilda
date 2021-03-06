@Monkey
Feature: Monkey Suite

  Background:
    Given flows defined over active traffgens in the reference topology
    And each flow has flow_id with monkey prefix
    And each flow has max bandwidth set to 10000

  @Prepare
  Scenario: Create flows for monkeys

    When creation request for each flow is successful

  @CheckFlows
  Scenario: Check the flows

    Then each flow is created and stored in TopologyEngine
    And each flow is in UP state
    And each flow can be read from Northbound
    And each flow has rules installed

  @CheckTraffic
  Scenario: Check the traffic

    Then each flow has traffic going with bandwidth not less than 10000

  @Cleanup
  Scenario: Delete the flows

    When each flow can be deleted

    Then each flow can not be read from Northbound
    And each flow can not be read from TopologyEngine
    And each flow has no rules installed
    And each flow has no traffic
