package com.monsanto.arch.cloudformation

import java.time.Instant
import java.time.format.{ DateTimeFormatter, DateTimeParseException }

import spray.json.JsonFormat
import scala.language.implicitConversions

/** A DSL to create consistent, type-safe AWS CloudFormation templates.
  *
  * The low-level DSL closely adheres to the objects and parameters in the
  * CloudFormation template JSON specification.  If you intend to use this
  * DSL to create CloudFormation templates, it would behoove you to familiarize
  * yourself with the [[http://docs.aws.amazon.com/AWSCloudFormation/latest/UserGuide/Welcome.html CloudFormation documentation]].
  *
  * In addition to the low-level, DSL, there are several higher-order builders
  * and convenience methods.  See [[com.monsanto.arch.cloudformation.model.simple.Builders]]
  * for more information on these helper methods.
  *
  * For a discussion of the features and approach of this library, read
  * our [[http://engineering.monsanto.com/2015/07/10/cloudformation-template-generator/ blog post]].
  *
  * Sample usage:
  *
  * {{{
  *    import com.monsanto.arch.cloudformation.model._
  *    import com.monsanto.arch.cloudformation.model.resource._
  *    import com.monsanto.arch.cloudformation.model.simple.Builders._
  *
  *    object SimpleVPC extends VPCWriter {
  *      val ownerParameter = StringParameter(
  *        name = "Owner",
  *        Description = Some("Individual responsible for this template"),
  *        MinLength = Some(StringBackedInt(1)),
  *        MaxLength = Some(StringBackedInt(64)),
  *        AllowedPattern = Some("[-_ a-zA-Z0-9]*"),
  *        ConstraintDescription = Some("Can contain only alphanumeric characters, spaces, dashes and underscores.")
  *      )
  *      val keyNameParameter = `AWS::EC2::KeyPair::KeyName_Parameter`(
  *        name = "KeyName",
  *        Description = Some("Name of an existing EC2 KeyPair to enable SSH access to the instances"),
  *        ConstraintDescription = Some("Value must be a valid AWS key pair name in your account.")
  *      )
  *      val allowSSHFromParameter = CidrBlockParameter(
  *        name = "AllowSSHFrom",
  *        Description = Some("The net block (CIDR) that SSH is available to.")
  *      )
  *      val simpleParameters = Seq(
  *        ownerParameter,
  *        keyNameParameter,
  *        allowSSHFromParameter
  *      )
  *
  *      val simpleConditions = Seq(
  *        Condition(
  *          name = "ShouldDisablePassword",
  *          function = `Fn::Equals`(
  *            a = ParameterRef(ownerParameter),
  *            b = StringToken("rms")
  *          )
  *        )
  *      )
  *
  *      val amazonLinuxAMIMapping = Mapping[AMIId](
  *        "AmazonLinuxAMI",
  *        Map(
  *          "us-east-1" -> Map("AMI" -> AMIId("ami-1ecae776")),
  *          "us-west-1" -> Map("AMI" -> AMIId("ami-d114f295")),
  *          "us-west-2" -> Map("AMI" -> AMIId("ami-e7527ed7")),
  *          "eu-west-1" -> Map("AMI" -> AMIId("ami-a10897d6"))
  *        )
  *      )
  *      val simpleMappings = Seq(amazonLinuxAMIMapping)
  *
  *      val simpleResourceAndOutputs = withVpc(CidrBlock(10, 0, 0, 0, 16)) { implicit vpc =>
  *        val (internetGatewayResource, gatewayToInternetResource) = withInternetGateway
  *        val publicRouteTable = withRouteTable("Public", 1)
  *        val publicRouteTableRoute = publicRouteTable.withRoute(
  *          visibility = "Public",
  *          routeTableOrdinal = 1,
  *          routeOrdinal = 1,
  *          gateway = Some(ResourceRef(internetGatewayResource))
  *        )
  *        val gatewayStuff = Template.fromResource(internetGatewayResource) ++
  *          gatewayToInternetResource ++
  *          publicRouteTable ++
  *          publicRouteTableRoute
  *        val withinAZ = withAZ("us-east-1a") { implicit az =>
  *          withSubnet("PubSubnet1", CidrBlock(10, 0, 0, 1, 24)) { implicit pubSubnet =>
  *            val bastionName = "bastion"
  *            val bastion = ec2(
  *              name = bastionName,
  *              InstanceType = "t2.micro",
  *              KeyName = ParameterRef(keyNameParameter),
  *              ImageId = `Fn::FindInMap`[AMIId](MappingRef(amazonLinuxAMIMapping), `AWS::Region`, "AMI"),
  *              SecurityGroupIds = Seq(),
  *              Tags = AmazonTag.fromName(bastionName),
  *              UserData = Some(`Fn::Base64`(
  *                `Fn::Join`("",
  *                  Seq[Token[String]](
  *                    "#!/bin/bash -v\n",
  *                    "yum update -y --security\n",
  *                    "# EOF\n"
  *                  )
  *                )
  *              ))
  *            )
  *            val sshToBastion = ParameterRef(allowSSHFromParameter) ->- 22 ->- bastion
  *            Template.fromSecurityGroupRoutable(bastion) ++
  *              bastion.map(_.withEIP("BastionEIP").andOutput("BastionEIP", "Bastion Host EIP")) ++
  *              Template.collapse(sshToBastion)
  *          }
  *        }
  *        gatewayStuff ++
  *          withinAZ
  *      }
  *      val simpleTemplate = simpleResourceAndOutputs ++
  *        Template(
  *          AWSTemplateFormatVersion = Some("2010-09-09"),
  *          Description = Some("Simple template"),
  *          Parameters = Some(simpleParameters),
  *          Conditions = Some(simpleConditions),
  *          Mappings = Some(simpleMappings),
  *          Resources = Seq.empty,
  *          Outputs = None
  *        )
  *      writeStaxModule("vpc-simple.json", simpleTemplate)
  *    }
  *    SimpleVPC
  * }}}
  *
  * The above code utilizes the DSL to create a simple AWS VPC utilizing a
  * single Availability Zone having a single public subnet and a single
  * "bastion" instance.  The template output is the IP address of the EIP
  * it creates.  It also shows examples of creating and using
  * Parameters and Mappings.  A Condition is created but not used.
  */

package object model {

  implicit def parameter2TokenString(parameter : StringParameter) : Token[String] = ParameterRef(parameter)

  implicit def eitherAfuncA2OptionEitherAfuncA[A](v: Either[A, AmazonFunctionCall[A]]): Option[Either[A, Token[A]]] =
    Some(v.right.map(Token.fromFunction[A]))

  /**
    * Provides a string interpolator to assist in the concatenation of
    *
    * The following:
    * {{{
    *  import com.monsanto.arch.cloudformation.model._
    *
    *  val param = ParameterRef(StringParameter("that"))
    *  val fun = aws"test\$param"
    * }}}
    *
    * Will generate the following FunctionCall definition:
    * {{{
    *  FunctionCallToken(`Fn::Join`("", Seq(StringToken("test"), param)))
    * }}}
    *
    */
  implicit class AwsStringInterpolator(val sc: StringContext) extends AnyVal {

    def aws(tokens: Token[String]*) = AwsStringInterpolation(sc, tokens)

  }

  implicit def lift2Option[A](a : A) : Option[A] = Option(a)

  import spray.json._
  import Token._
  /**
    * This is to assist in creating Output[Token[String]] objects as it allows the formatter to be in scope.
    */
  implicit lazy val stringTokenFormat = implicitly[JsonFormat[Token[String]]]

  implicit val instantFormat: JsonFormat[Instant] = {
    import spray.json._
    new JsonFormat[Instant] {
      def write(v: Instant): JsValue = JsString(v.toString) // ISO8601
      def read(v: JsValue): Instant = v match {
        case JsString(s) => try {
          Instant.from(DateTimeFormatter.ISO_INSTANT.parse(s))
        } catch {
          case _: DateTimeParseException => deserializationError("instant not readable")
        }
        case _ => deserializationError("instant not a string")
      }
    }
  }
}
